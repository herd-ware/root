/*
 * File: id.scala
 * Created Date: 2023-03-08 01:51:25 pm                                        *
 * Author: Mathieu Escouteloup                                                 *
 * -----                                                                       *
 * Last Modified: 2023-04-06 08:39:34 pm
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

import herd.common.gen._
import herd.common.field._
import herd.common.isa.riscv._
import herd.core.aubrac.common._
import herd.core.aubrac.back.{Decoder, SlctImm, SlctSource, BackPortBus, StageBus}
import herd.core.aubrac.back.{OP, INTUNIT, INTUOP}
import herd.core.aubrac.back.csr.{CsrDecoderBus}


class IdStage(p: BackParams) extends Module {
  val io = IO(new Bundle {
    val b_hart = if (p.useField) Some(Vec(p.nHart, new RsrcIO(p.nHart, p.nField, p.nHart))) else None
    val b_back = if (p.useField) Some(Vec(p.nBackPort, new RsrcIO(p.nHart, p.nField, p.nBackPort))) else None

    val i_flush = Input(Vec(p.nHart, Bool()))
    val o_flush = Output(Vec(p.nHart, Bool()))

    val b_in = Vec(p.nBackPort, Flipped(new GenRVIO(p, new BackPortBus(p.debug, p.nHart, p.nAddrBit, p.nInstrBit), UInt(0.W))))

    val i_csr = Input(Vec(p.nHart, new CsrDecoderBus()))
    val o_stop = Output(Vec(p.nHart, Bool()))
    val o_stage = Output(Vec(p.nBackPort, new StageBus(p.nHart, p.nAddrBit, p.nInstrBit)))

    val b_out = Vec(p.nBackPort, new GenRVIO(p, new IssCtrlBus(p), UInt(0.W)))
  })

  val w_lock = Wire(Vec(p.nBackPort, Bool()))
  val w_wait = Wire(Vec(p.nBackPort, Bool()))
  val w_flush = Wire(Vec(p.nBackPort, Bool()))

  // ******************************
  //        BACK PORT STATUS
  // ******************************
  val w_back_valid = Wire(Vec(p.nBackPort, Bool()))
  val w_back_flush = Wire(Vec(p.nBackPort, Bool()))

  for (bp <- 0 until p.nBackPort) {
    if (p.useField) {
      w_back_valid(bp) := io.b_back.get(bp).valid & ~io.b_back.get(bp).flush
      w_back_flush(bp) := io.b_back.get(bp).flush | io.i_flush(io.b_in(bp).ctrl.get.hart)
    } else {
      w_back_valid(bp) := true.B
      w_back_flush(bp) := io.i_flush(io.b_in(bp).ctrl.get.hart)
    } 
  } 

  // ******************************
  //            DECODER
  // ******************************
  val m_decoder = Seq.fill(p.nBackPort){Module(new Decoder(p))}
  
  for (bp <- 0 until p.nBackPort) {
    m_decoder(bp).io.i_instr := io.b_in(bp).ctrl.get.instr
    m_decoder(bp).io.i_csr := io.i_csr(io.b_in(bp).ctrl.get.hart)
  }  

  // ******************************
  //             IMM
  // ******************************
  val m_imm1 = Seq.fill(p.nBackPort){Module(new SlctImm(p.nInstrBit, p.nDataBit))}
  val m_imm2 = Seq.fill(p.nBackPort){Module(new SlctImm(p.nInstrBit, p.nDataBit))}

  for (bp <- 0 until p.nBackPort) {
    m_imm1(bp).io.i_instr := io.b_in(bp).ctrl.get.instr
    m_imm1(bp).io.i_imm_type := m_decoder(bp).io.o_data.imm1type

    m_imm2(bp).io.i_instr := io.b_in(bp).ctrl.get.instr
    m_imm2(bp).io.i_imm_type := m_decoder(bp).io.o_data.imm2type
  }

  // ******************************
  //             SOURCE
  // ******************************
  val m_s1_src = Seq.fill(p.nBackPort){Module(new SlctSource(p.nInstrBit, p.nDataBit, false))}
  val m_s2_src = Seq.fill(p.nBackPort){Module(new SlctSource(p.nInstrBit, p.nDataBit, false))}
  val m_s3_src = Seq.fill(p.nBackPort){Module(new SlctSource(p.nInstrBit, p.nDataBit, false))}

  for (bp <- 0 until p.nBackPort) {
    // ------------------------------
    //               S1
    // ------------------------------
    m_s1_src(bp).io.i_src_type := m_decoder(bp).io.o_data.s1type
    m_s1_src(bp).io.i_imm1 := m_imm1(bp).io.o_val
    m_s1_src(bp).io.i_imm2 := m_imm2(bp).io.o_val
    m_s1_src(bp).io.i_pc := io.b_in(bp).ctrl.get.pc
    m_s1_src(bp).io.i_instr := io.b_in(bp).ctrl.get.instr

    // ------------------------------
    //               S2
    // ------------------------------
    m_s2_src(bp).io.i_src_type := m_decoder(bp).io.o_data.s2type
    m_s2_src(bp).io.i_imm1 := m_imm1(bp).io.o_val
    m_s2_src(bp).io.i_imm2 := m_imm2(bp).io.o_val
    m_s2_src(bp).io.i_pc := io.b_in(bp).ctrl.get.pc
    m_s2_src(bp).io.i_instr := io.b_in(bp).ctrl.get.instr

    // ------------------------------
    //               S3
    // ------------------------------
    m_s3_src(bp).io.i_src_type := m_decoder(bp).io.o_data.s3type
    m_s3_src(bp).io.i_imm1 := m_imm1(bp).io.o_val
    m_s3_src(bp).io.i_imm2 := m_imm2(bp).io.o_val
    m_s3_src(bp).io.i_pc := io.b_in(bp).ctrl.get.pc
    m_s3_src(bp).io.i_instr := io.b_in(bp).ctrl.get.instr
  }

  // ******************************
  //            FLUSH
  // ******************************
  val r_flush = RegInit(VecInit(Seq.fill(p.nHart)(false.B)))

  for (h <- 0 until p.nHart) {
    when (r_flush(h)) {
      if (p.useField) {
        r_flush(h) := ~(io.i_flush(h) | io.b_hart.get(h).flush)
      } else {
        r_flush(h) := ~io.i_flush(h)
      }      
    }.otherwise {
      for (bp <- 0 until p.nBackPort) {
        when (io.b_in(bp).valid & (h.U === io.b_in(bp).ctrl.get.hart) & ~w_flush(bp) & ~w_wait(bp) & ~w_lock(bp) & m_decoder(bp).io.o_trap.valid) {
          r_flush(h) := true.B
        }
      }      
    }
  }

  for (bp <- 0 until p.nBackPort) {
    w_flush(bp) := r_flush(io.b_in(bp).ctrl.get.hart) | w_back_flush(bp)
  }

  for (h <- 0 until p.nHart) {
    if (p.useBranchReg) {
      io.o_flush(h) := r_flush(h)
    } else {
      io.o_flush(h) := false.B
      for (bp <- 0 until p.nBackPort) {
        when (io.b_in(bp).valid & (h.U === io.b_in(bp).ctrl.get.hart) & ~w_flush(bp) & ~w_wait(bp) & ~w_lock(bp) & m_decoder(bp).io.o_trap.valid) {
          io.o_flush(h) := true.B
        }
      }      
    }
  }

  // ******************************
  //            OUTPUTS
  // ******************************
  // ------------------------------
  //              BUS
  // ------------------------------
  val m_out = Seq.fill(p.nBackPort){Module(new GenReg(p, new IssCtrlBus(p), UInt(0.W), false, false, true))}

  // Lock register
  for (bp0 <- 0 until p.nBackPort) {
    w_lock(bp0) := ~m_out(bp0).io.b_in.ready
    for (bp1 <- 0 until p.nBackPort) {
      if (bp1 != bp0) {
        when (~m_out(bp1).io.b_in.ready & (m_out(bp1).io.o_val.ctrl.get.info.hart === io.b_in(bp0).ctrl.get.hart)) {
          w_lock(bp0) := true.B
        }
      }
    }
  }

  // Wait
  for (bp <- 0 until p.nBackPort) {
    w_wait(bp) := false.B
  }

  for (bp0 <- 0 until p.nBackPort) {
    for (bp1 <- 0 until p.nBackPort) {
      if (bp0 != bp1) {
        when (w_lock(bp1) & (io.b_in(bp1).ctrl.get.hart === io.b_in(bp0).ctrl.get.hart)) {
          w_wait(bp0) := true.B
        }
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

    m_out(bp).io.b_in.ctrl.get.info := m_decoder(bp).io.o_info
    m_out(bp).io.b_in.ctrl.get.info.pc := io.b_in(bp).ctrl.get.pc
    m_out(bp).io.b_in.ctrl.get.trap := m_decoder(bp).io.o_trap

    m_out(bp).io.b_in.ctrl.get.int := m_decoder(bp).io.o_int
    m_out(bp).io.b_in.ctrl.get.lsu := m_decoder(bp).io.o_lsu
    m_out(bp).io.b_in.ctrl.get.csr := m_decoder(bp).io.o_csr
    m_out(bp).io.b_in.ctrl.get.gpr := m_decoder(bp).io.o_gpr

    m_out(bp).io.b_in.ctrl.get.ext := m_decoder(bp).io.o_ext

    m_out(bp).io.b_in.ctrl.get.hpc := DontCare
    
    m_out(bp).io.b_in.ctrl.get.data.s1 := m_s1_src(bp).io.o_val
    m_out(bp).io.b_in.ctrl.get.data.s2 := m_s2_src(bp).io.o_val
    m_out(bp).io.b_in.ctrl.get.data.s3 := m_s3_src(bp).io.o_val
    m_out(bp).io.b_in.ctrl.get.data.rs1 := m_decoder(bp).io.o_data.rs1
    m_out(bp).io.b_in.ctrl.get.data.rs2 := m_decoder(bp).io.o_data.rs2
    m_out(bp).io.b_in.ctrl.get.data.s1_is_reg := (m_decoder(bp).io.o_data.s1type === OP.XREG)
    m_out(bp).io.b_in.ctrl.get.data.s2_is_reg := (m_decoder(bp).io.o_data.s2type === OP.XREG)
    m_out(bp).io.b_in.ctrl.get.data.s3_is_reg := (m_decoder(bp).io.o_data.s3type === OP.XREG)

    io.b_out(bp) <> m_out(bp).io.b_out
  }

  // ------------------------------
  //             LOCK
  // ------------------------------
  for (bp <- 0 until p.nBackPort) {
    io.b_in(bp).ready := w_flush(bp) | (~w_wait(bp) & ~w_lock(bp))
  }

  // ******************************
  //             STAGE
  // ******************************  
  for (h <- 0 until p.nHart) {
    io.o_stop(h) := false.B 

    for (bp <- 0 until p.nBackPort) {
      when (io.b_in(bp).valid & (h.U === io.b_in(bp).ctrl.get.hart) & ~w_lock(bp) & ~w_flush(bp)) {
        io.o_stop(h) := m_decoder(bp).io.o_trap.valid
      }
    }
  }

  for (bp <- 0 until p.nBackPort) {
    io.o_stage(bp).valid := io.b_in(bp).valid
    io.o_stage(bp).hart := io.b_in(bp).ctrl.get.hart
    io.o_stage(bp).pc := io.b_in(bp).ctrl.get.pc
    io.o_stage(bp).instr := io.b_in(bp).ctrl.get.instr
    io.o_stage(bp).exc_gen := m_decoder(bp).io.o_trap.gen
    io.o_stage(bp).end := io.b_in(bp).valid & m_decoder(bp).io.o_info.end
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
      val s2 = UInt(p.nDataBit.W)
      val s3 = UInt(p.nDataBit.W)
    }))

    for (bp <- 0 until p.nBackPort) {
      w_dfp(bp).pc := m_out(bp).io.o_reg.ctrl.get.info.pc
      w_dfp(bp).instr := m_out(bp).io.o_reg.ctrl.get.info.instr
      w_dfp(bp).s1 := m_out(bp).io.o_reg.ctrl.get.data.s1
      w_dfp(bp).s2 := m_out(bp).io.o_reg.ctrl.get.data.s2
      w_dfp(bp).s3 := m_out(bp).io.o_reg.ctrl.get.data.s3 
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

object IdStage extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new IdStage(BackConfigBase), args)
}