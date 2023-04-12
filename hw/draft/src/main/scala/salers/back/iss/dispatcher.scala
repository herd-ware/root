/*
 * File: dispatcher.scala                                                      *
 * Created Date: 2023-03-08 01:51:25 pm                                        *
 * Author: Mathieu Escouteloup                                                 *
 * -----                                                                       *
 * Last Modified: 2023-04-12 08:41:30 am                                       *
 * Modified By: Mathieu Escouteloup                                            *
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


class SlctPort(nVariant: Int, nUnit: Int) extends Module {
  val io = IO(new Bundle {
    val i_req = Input(Vec(nVariant, Bool()))

    val i_free = Input(Vec(nVariant, Vec(nUnit, Bool())))

    val o_av = Output(Bool())
    val o_port = Output(UInt(log2Ceil(nUnit).W))
    val o_free = Output(Vec(nVariant, Vec(nUnit, Bool())))
  })

  // ******************************
  //             FREE
  // ******************************
  val w_free = Wire(Vec(nVariant, Vec(nUnit, Bool())))

  for (v <- 0 until nVariant) {
    for (u <- 0 until nUnit) {
      w_free(v)(u) := io.i_free(v)(u)
    }
  }

  // ******************************
  //             LOGIC
  // ******************************
  val w_av = Wire(Vec(nVariant + 1, Bool()))
  val w_port = Wire(UInt(log2Ceil(nUnit + 1).W))

  w_av(0) := false.B
  w_port := DontCare
  for (v <- 0 until nVariant) {
    when (io.i_req(v)) {
      w_av(v + 1) := w_av(v) | w_free(v).asUInt.orR
    }.otherwise {
      w_av(v + 1) := w_av(v)
    }    
    when (~w_av(v)) {
      w_port := PriorityEncoder(w_free(v).asUInt)
    }
  }    

  // ******************************
  //             OUTPUT
  // ******************************
  io.o_av := w_av.asUInt.orR
  io.o_port := w_port

  for (v <- 0 until nVariant) {
    io.o_free(v) := io.i_free(v)
    io.o_free(v)(w_port) := io.i_free(v)(w_port) & ~io.i_req.asUInt.orR
  }
}

class SlctNPort(nBackPort: Int, nVariant: Int, nUnit: Int) extends Module {
  val io = IO(new Bundle {
    val i_req = Input(Vec(nBackPort, Vec(nVariant, Bool())))

    val i_free = Input(Vec(nVariant, Vec(nUnit, Bool())))

    val o_av = Output(Vec(nBackPort, Bool()))
    val o_port = Output(Vec(nBackPort, UInt(log2Ceil(nUnit).W)))
  })

  val m_slct = Seq.fill(nBackPort) {Module(new SlctPort(nVariant, nUnit))}

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
  val w_req_balu = if (p.nBAlu > 0) Some(Wire(Vec(p.nBackPort, Bool()))) else None
  val w_req_clmul = if (p.nClMul > 0) Some(Wire(Vec(p.nBackPort, Bool()))) else None

  for (bp <- 0 until p.nBackPort) {
    w_req_alu(bp) := io.b_port(bp).valid & (io.b_port(bp).unit === INTUNIT.ALU)
    w_req_bru(bp) := io.b_port(bp).valid & (io.b_port(bp).unit === INTUNIT.BRU)
    if (p.nMulDiv > 0) w_req_muldiv.get(bp) := io.b_port(bp).valid & (io.b_port(bp).unit === INTUNIT.MULDIV)
    if (p.nBAlu > 0) w_req_balu.get(bp) := io.b_port(bp).valid & (io.b_port(bp).unit === INTUNIT.BALU)
    if (p.nClMul > 0) w_req_clmul.get(bp) := io.b_port(bp).valid & (io.b_port(bp).unit === INTUNIT.CLMUL)
  }

  // ******************************
  //          ALU / BALU
  // ******************************
  var nAluVar: Int = 1
  if (p.nBAlu > 0) nAluVar = nAluVar + 1

  val m_alu = Module(new SlctNPort(p.nBackPort, nAluVar, p.nAlu))
  
  m_alu.io.i_free(0) := io.i_free.alu
  if (p.nBAlu > 0) {
    m_alu.io.i_free(1) := io.i_free.balu.get
  }
  for (bp <- 0 until p.nBackPort) {
    m_alu.io.i_req(bp)(0) := w_req_alu(bp)
    if (p.nBAlu > 0) {
      m_alu.io.i_req(bp)(1) := w_req_balu.get(bp)
    }
  }
  
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

    if (p.nBAlu > 0) {
      when (io.b_port(bp).unit === INTUNIT.BALU) {
        if (p.nBAlu == p.nBackPort) {
          io.b_port(bp).ready := true.B
          io.b_port(bp).port := bp.U
        } else {
          io.b_port(bp).ready := io.b_port(bp).valid & m_alu.io.o_av(bp)
          io.b_port(bp).port := m_alu.io.o_port(bp)
        }
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
    var nMulDivVar: Int = 1
    if (p.nClMul > 0) nMulDivVar = nMulDivVar + 1

    val m_muldiv = Module(new SlctNPort(p.nBackPort, nMulDivVar, p.nMulDiv))

    
    m_muldiv.io.i_free(0) := io.i_free.muldiv.get
    if (p.nClMul > 0) {     
      m_muldiv.io.i_free(1) := io.i_free.clmul.get
    }
    for (bp <- 0 until p.nBackPort) {
      m_muldiv.io.i_req(bp)(0) := w_req_muldiv.get(bp)
      if (p.nClMul > 0) {
         m_muldiv.io.i_req(bp)(1) := w_req_clmul.get(bp)
      }
    }

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

      if (p.nClMul > 0) {
        when (io.b_port(bp).unit === INTUNIT.CLMUL) {
          if (p.nClMul == p.nBackPort) {
            io.b_port(bp).ready := true.B
            io.b_port(bp).port := bp.U
          } else {
            io.b_port(bp).ready := io.b_port(bp).valid & m_muldiv.io.o_av(bp)
            io.b_port(bp).port := m_muldiv.io.o_port(bp)
          }
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
    if (p.nBAlu > 0) dontTouch(w_req_balu.get)
    if (p.nClMul > 0) dontTouch(w_req_clmul.get)
  }
}

object SlctPort extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new SlctPort(1, 4), args)
}

object SlctNPort extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new SlctNPort(2, 4, 4), args)
}

object Dispatcher extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new Dispatcher(DispatcherConfigBase), args)
}