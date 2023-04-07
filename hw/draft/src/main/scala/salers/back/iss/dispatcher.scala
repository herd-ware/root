/*
 * File: dispatcher.scala
 * Created Date: 2023-03-08 01:51:25 pm                                        *
 * Author: Mathieu Escouteloup                                                 *
 * -----                                                                       *
 * Last Modified: 2023-04-05 10:26:46 am
 * Modified By: Mathieu Escouteloup
 * -----                                                                       *
 * License: See LICENSE.md                                                     *
 * Copyright (c) 2023 HerdWare                                                 *
 * -----                                                                       *
 * Description:                                                                *
 */


package herd.core.salers.back

import chisel3._
import chisel3.util._

import herd.core.aubrac.back.{INTUNIT}


class SlctPort(nUnit: Int) extends Module {
  val io = IO(new Bundle {
    val i_req = Input(Bool())

    val i_free = Input(Vec(nUnit, Bool()))

    val o_av = Output(Bool())
    val o_port = Output(UInt(log2Ceil(nUnit).W))
    val o_free = Output(Vec(nUnit, Bool()))
  })

  // ******************************
  //             FREE
  // ******************************
  val w_free = Wire(Vec(nUnit, Bool()))

  for (u <- 0 until nUnit) {
    w_free(u) := io.i_free(u)
  }

  // ******************************
  //             LOGIC
  // ******************************
  val w_av = Wire(Bool())
  val w_port = Wire(UInt(log2Ceil(nUnit + 1).W))

  w_av := w_free.asUInt.orR
  w_port := PriorityEncoder(w_free.asUInt)  

  // ******************************
  //             OUTPUT
  // ******************************
  io.o_av := w_av
  io.o_port := w_port

  io.o_free := io.i_free
  io.o_free(w_port) := io.i_free(w_port) & ~io.i_req
}

class SlctNPort(nBackPort: Int, nUnit: Int) extends Module {
  val io = IO(new Bundle {
    val i_req = Input(Vec(nBackPort, Bool()))

    val i_free = Input(Vec(nUnit, Bool()))

    val o_av = Output(Vec(nBackPort, Bool()))
    val o_port = Output(Vec(nBackPort, UInt(log2Ceil(nUnit).W)))
  })

  val m_slct = Seq.fill(nBackPort) {Module(new SlctPort(nUnit))}

  m_slct(0).io.i_req := io.i_req(0)
  m_slct(0).io.i_free := io.i_free

  for (bp <- 1 until nBackPort) {
    m_slct(bp).io.i_req := io.i_req(bp)
    m_slct(bp).io.i_free := m_slct(bp - 1).io.o_free
  }

  for (bp <- 0 until nBackPort) {
    io.o_av(bp):= m_slct(bp).io.o_av
    io.o_port(bp):= m_slct(bp).io.o_port
  }
}

class Dispatcher(p: DispatcherParams) extends Module {
  val io = IO(new Bundle {
    val b_port = Vec(p.nBackPort, Flipped(new DispatcherIO(p)))

    val i_free = Input(new FreeBus(p))
  })

  // Default
  for (bp <- 0 until p.nBackPort) {
    io.b_port(bp).ready := false.B
    io.b_port(bp).port := 0.U
  }

  // ******************************
  //            REQUESTS
  // ******************************
  val w_req_alu = Wire(Vec(p.nBackPort, Bool()))
  val w_req_bru = Wire(Vec(p.nBackPort, Bool()))
  val w_req_muldiv = if (p.nMulDiv > 0) Some(Wire(Vec(p.nBackPort, Bool()))) else None

  for (bp <- 0 until p.nBackPort) {
    w_req_alu(bp) := io.b_port(bp).valid & (io.b_port(bp).unit === INTUNIT.ALU)
    w_req_bru(bp) := io.b_port(bp).valid & (io.b_port(bp).unit === INTUNIT.BRU)
    if (p.nMulDiv > 0) w_req_muldiv.get(bp) := io.b_port(bp).valid & (io.b_port(bp).unit === INTUNIT.MULDIV)
  }

  // ******************************
  //              ALU
  // ******************************
  val m_alu = Module(new SlctNPort(p.nBackPort, p.nAlu))

  m_alu.io.i_req := w_req_alu
  m_alu.io.i_free := io.i_free.alu
  
  for (bp <- 0 until p.nBackPort) {
    when (io.b_port(bp).unit === INTUNIT.ALU) {
      if (p.nAlu == p.nBackPort) {
        io.b_port(bp).ready := true.B
        io.b_port(bp).port := bp.U
      } else {
        io.b_port(bp).ready := io.b_port(bp).valid & m_alu.io.o_av(bp)
        io.b_port(bp).port := m_alu.io.o_port(bp)
      }
    }
  }

  // ******************************
  //              BRU
  // ******************************
  val w_bru_free = Wire(Vec(p.nHart, Vec(p.nBackPort, Bool())))

  for (h <- 0 until p.nHart) {
    w_bru_free(h)(0) := io.i_free.bru(h)

    for (bp <- 1 until p.nBackPort) {
      w_bru_free(h)(bp) := w_bru_free(h)(bp - 1) & (~io.b_port(bp - 1).valid | (io.b_port(bp - 1).unit =/= INTUNIT.BRU) | (h.U =/= io.b_port(bp - 1).hart))
    }
  }

  for (bp <- 0 until p.nBackPort) {
    when (io.b_port(bp).unit === INTUNIT.BRU) {
      io.b_port(bp).ready := io.b_port(bp).valid & w_bru_free(io.b_port(bp).hart)(bp)
      io.b_port(bp).port := io.b_port(bp).hart
    }
  }

  // ******************************
  //            MULDIV
  // ******************************
  if (p.nMulDiv > 0) {
    val m_muldiv = Module(new SlctNPort(p.nBackPort, p.nMulDiv))

    m_muldiv.io.i_req := w_req_muldiv.get
    m_muldiv.io.i_free := io.i_free.muldiv.get

    for (bp <- 0 until p.nBackPort) {
      when (io.b_port(bp).unit === INTUNIT.MULDIV) {
        if (p.nMulDiv == p.nBackPort) {
          io.b_port(bp).ready := true.B
          io.b_port(bp).port := bp.U
        } else {
          io.b_port(bp).ready := io.b_port(bp).valid & m_muldiv.io.o_av(bp)
          io.b_port(bp).port := m_muldiv.io.o_port(bp)
        }
      }
    }
  }

  // ******************************
  //            DEBUG
  // ******************************
  if (p.debug) {
    dontTouch(w_req_alu)
    dontTouch(w_req_bru)
    if (p.nMulDiv > 0) dontTouch(w_req_muldiv.get)
  }
}

object SlctPort extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new SlctPort(4), args)
}

object SlctNPort extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new SlctNPort(4, 4), args)
}

object Dispatcher extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new Dispatcher(DispatcherConfigBase), args)
}