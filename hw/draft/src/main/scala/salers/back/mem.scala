/*
 * File: mem.scala                                                             *
 * Created Date: 2023-03-08 01:51:25 pm                                        *
 * Author: Mathieu Escouteloup                                                 *
 * -----                                                                       *
 * Last Modified: 2023-04-11 04:28:18 pm                                       *
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

import herd.common.gen._
import herd.common.field._
import herd.core.aubrac.common._
import herd.core.aubrac.back.{BypassBus,ResultBus,StageBus}
import herd.core.aubrac.back.{EXT}
import herd.core.aubrac.back.csr.{CsrReadIO}
import herd.core.aubrac.nlp.{BranchInfoBus}


class MemStage(p: BackParams) extends Module {  
  val io = IO(new Bundle {
    val b_hart = if (p.useField) Some(Vec(p.nHart, new RsrcIO(p.nHart, p.nField, p.nHart))) else None
    val b_back = if (p.useField) Some(Vec(p.nBackPort, new RsrcIO(p.nHart, p.nField, p.nBackPort))) else None

    val i_flush = Input(Vec(p.nHart, Bool()))

    val b_in = Vec(p.nBackPort, Flipped(new GenRVIO(p, new MemCtrlBus(p), new ResultBus(p.nDataBit))))

    val o_stage = Output(Vec(p.nBackPort, new StageBus(p.nHart, p.nAddrBit, p.nInstrBit)))

    val b_csr = Vec(p.nHart, Flipped(new CsrReadIO(p.nDataBit)))
    val o_byp = Output(Vec(p.nBackPort, new BypassBus(p.nHart, p.nDataBit)))

    val b_out = Vec(p.nBackPort, new GenRVIO(p, new WbCtrlBus(p), new ResultBus(p.nDataBit)))
  })

  val w_lock = Wire(Vec(p.nBackPort, Bool()))
  val w_wait = Wire(Vec(p.nBackPort, Bool()))
  val w_wait_bp = Wire(Vec(p.nBackPort, Bool()))
  val w_wait_csr = Wire(Vec(p.nBackPort, Bool()))
  val w_flush = Wire(Vec(p.nBackPort, Bool()))

  // ******************************
  //        BACK PORT STATUS
  // ******************************
  val w_back_valid = Wire(Vec(p.nBackPort, Bool()))
  val w_back_flush = Wire(Vec(p.nBackPort, Bool()))

  for (bp <- 0 until p.nBackPort) {
    if (p.useField) {
      w_back_valid(bp) := io.b_back.get(bp).valid & ~io.b_back.get(bp).flush
      w_back_flush(bp) := io.b_back.get(bp).flush | io.i_flush(io.b_in(bp).ctrl.get.info.hart)
    } else {
      w_back_valid(bp) := true.B
      w_back_flush(bp) := io.i_flush(io.b_in(bp).ctrl.get.info.hart)
    } 
  } 

  // ******************************
  //            CSR PORT
  // ******************************
  for (bp <- 0 until p.nBackPort) {
    w_wait_csr(bp) := false.B
  }

  for (h <- 0 until p.nHart) {
    io.b_csr(h) := DontCare
    io.b_csr(h).valid := false.B

    for (bp <- 0 until p.nBackPort) {
      when (io.b_in(bp).valid & w_back_valid(bp) & io.b_in(bp).ctrl.get.csr.read & (h.U === io.b_in(bp).ctrl.get.info.hart)) {
        io.b_csr(h).valid := ~w_flush(bp) & ~w_lock(bp)
        io.b_csr(h).addr := io.b_in(bp).data.get.s3(11,0)
      }
    }
  }  

  // ******************************
  //            FLUSH
  // ******************************
  for (bp <- 0 until p.nBackPort) {
    w_flush(bp) := w_back_flush(bp)
  }

  // ******************************
  //           REGISTERS
  // ******************************
  val m_out = Seq.fill(p.nBackPort){Module(new GenReg(p, new WbCtrlBus(p), new ResultBus(p.nDataBit), false, false, true))}

  // Lock register
  for (bp0 <- 0 until p.nBackPort) {
    w_lock(bp0) := ~m_out(bp0).io.b_in.ready  
    for (bp1 <- 0 until p.nBackPort) {
      if (bp1 != bp0) {
        when (~m_out(bp1).io.b_in.ready & (m_out(bp1).io.o_val.ctrl.get.info.hart === io.b_in(bp0).ctrl.get.info.hart)) {
          w_lock(bp0) := true.B
        }
      }
    }
  }

  // Wait
  for (bp <- 0 until p.nBackPort) {
    w_wait_bp(bp) := false.B
    w_wait(bp) := (w_lock(bp) | w_wait_csr(bp) | w_wait_bp(bp))
  }

  for (bp0 <- 0 until p.nBackPort) {
    for (bp1 <- 0 until bp0) {
      when (io.b_in(bp1).valid & (io.b_in(bp1).ctrl.get.info.hart === io.b_in(bp0).ctrl.get.info.hart) & w_wait(bp1)) {
        w_wait_bp(bp0) := true.B
      }
    }
  }

  // Update register
  for (bp <- 0 until p.nBackPort) {
    if (p.useField) {
      m_out(bp).io.i_flush := io.b_back.get(bp).flush
    } else {
      m_out(bp).io.i_flush := false.B
    } 

    m_out(bp).io.b_in.valid := io.b_in(bp).valid & w_back_valid(bp) & ~w_flush(bp) & ~w_wait(bp)

    m_out(bp).io.b_in.ctrl.get.info := io.b_in(bp).ctrl.get.info
    m_out(bp).io.b_in.ctrl.get.trap := io.b_in(bp).ctrl.get.trap

    m_out(bp).io.b_in.ctrl.get.lsu := io.b_in(bp).ctrl.get.lsu
    m_out(bp).io.b_in.ctrl.get.csr := io.b_in(bp).ctrl.get.csr
    m_out(bp).io.b_in.ctrl.get.gpr := io.b_in(bp).ctrl.get.gpr

    m_out(bp).io.b_in.ctrl.get.ext := io.b_in(bp).ctrl.get.ext
    m_out(bp).io.b_in.ctrl.get.hpc := io.b_in(bp).ctrl.get.hpc

    m_out(bp).io.b_in.data.get := io.b_in(bp).data.get
    m_out(bp).io.b_in.data.get.res := Mux(io.b_in(bp).ctrl.get.csr.read, io.b_csr(io.b_in(bp).ctrl.get.info.hart).data, io.b_in(bp).data.get.res)
  }

  // ******************************
  //            OUTPUTS
  // ******************************
  for (bp <- 0 until p.nBackPort) {
    // ------------------------------
    //             BUS
    // ------------------------------
    io.b_out(bp) <> m_out(bp).io.b_out

    // ------------------------------
    //             LOCK
    // ------------------------------
    io.b_in(bp).ready := w_flush(bp) | ~w_wait(bp)
  }

  // ******************************
  //             BYPASS
  // ******************************
  for (bp <- 0 until p.nBackPort) {
    io.o_byp(bp).valid := io.b_in(bp).valid & io.b_in(bp).ctrl.get.gpr.en
    io.o_byp(bp).ready := ~(io.b_in(bp).ctrl.get.lsu.load | (io.b_in(bp).ctrl.get.ext.ext =/= EXT.NONE) | w_wait_csr(bp))
    io.o_byp(bp).hart := io.b_in(bp).ctrl.get.info.hart
    io.o_byp(bp).addr := io.b_in(bp).ctrl.get.gpr.addr
    io.o_byp(bp).data := Mux(io.b_in(bp).ctrl.get.csr.read, io.b_csr(io.b_in(bp).ctrl.get.info.hart).data, io.b_in(bp).data.get.res)
  }
  
  // ******************************
  //             STAGE
  // ******************************  
  for (bp <- 0 until p.nBackPort) {
    io.o_stage(bp).valid := io.b_in(bp).valid
    io.o_stage(bp).hart := io.b_in(bp).ctrl.get.info.hart
    io.o_stage(bp).pc := io.b_in(bp).ctrl.get.info.pc
    io.o_stage(bp).instr := io.b_in(bp).ctrl.get.info.instr
    io.o_stage(bp).exc_gen := io.b_in(bp).ctrl.get.trap.gen
    io.o_stage(bp).end := io.b_in(bp).ctrl.get.info.end
  }

  // ******************************
  //             FIELD
  // ******************************
  if (p.useField) {
    for (h <- 0 until p.nHart) {
      io.b_hart.get(h).free := true.B
    }

    for (bp <- 0 until p.nBackPort) {
      io.b_back.get(bp).free := ~m_out(bp).io.o_val.valid
    }    
  }  

  // ******************************
  //            DEBUG
  // ******************************
  if (p.debug) {
    // ------------------------------
    //            SIGNALS
    // ------------------------------

    // ------------------------------
    //         DATA FOOTPRINT
    // ------------------------------
    val w_dfp = Wire(Vec(p.nBackPort, new Bundle {
      val pc = UInt(p.nAddrBit.W)
      val instr = UInt(p.nInstrBit.W)
      val s1 = UInt(p.nDataBit.W)
      val s3 = UInt(p.nDataBit.W)
      val res = UInt(p.nDataBit.W)
    }))

    for (bp <- 0 until p.nBackPort) {
      w_dfp(bp).pc := m_out(bp).io.o_val.ctrl.get.info.pc
      w_dfp(bp).instr := m_out(bp).io.o_val.ctrl.get.info.instr
      w_dfp(bp).s1 := m_out(bp).io.o_reg.data.get.s1
      w_dfp(bp).s3 := m_out(bp).io.o_reg.data.get.s3
      w_dfp(bp).res := m_out(bp).io.o_reg.data.get.res      
    }
    
    dontTouch(w_dfp)
    
    // ------------------------------
    //       EXECUTION TRACKER
    // ------------------------------
    for (bp <- 0 until p.nBackPort) {
      m_out(bp).io.b_in.ctrl.get.etd.get := io.b_in(bp).ctrl.get.etd.get

      dontTouch(m_out(bp).io.o_val.ctrl.get.etd.get)
    }
  }

}

object MemStage extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new MemStage(BackConfigBase), args)
}