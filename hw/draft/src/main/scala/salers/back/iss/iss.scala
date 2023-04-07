/*
 * File: iss.scala
 * Created Date: 2023-03-08 01:51:25 pm                                        *
 * Author: Mathieu Escouteloup                                                 *
 * -----                                                                       *
 * Last Modified: 2023-04-06 08:55:50 pm
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
import herd.core.aubrac.back.{SlctSize}
import herd.core.aubrac.back.{GprReadIO, DataBus, StageBus}
import herd.core.aubrac.back.{OP, INTUNIT, INTUOP}


class IssStage(p: BackParams) extends Module {
  val io = IO(new Bundle {
    val b_hart = if (p.useField) Some(Vec(p.nHart, new RsrcIO(p.nHart, p.nField, p.nHart))) else None
    val b_back = if (p.useField) Some(Vec(p.nBackPort, new RsrcIO(p.nHart, p.nField, p.nBackPort))) else None

    val i_flush = Input(Vec(p.nHart, Bool()))

    val b_in = Vec(p.nBackPort, Flipped(new GenRVIO(p, new IssCtrlBus(p), UInt(0.W))))

    val o_br_next = Output(Vec(p.nHart, new BranchBus(p.nAddrBit)))
    val i_end = Input(Vec(p.nHart, Bool()))
    val i_pend = Input(Vec(p.nHart, Bool()))

    val b_rs = Vec(p.nGprReadLog, Flipped(new GprReadIO(p)))

    val o_hpc_srcdep = Output(Vec(p.nHart, UInt(8.W)))

    val i_free = Input(new FreeBus(p))
    val o_stage = Output(Vec(p.nBackPort, new StageBus(p.nHart, p.nAddrBit, p.nInstrBit)))

    val b_out = Vec(p.nBackPort, new GenRVIO(p, new Ex0CtrlBus(p), new DataBus(p.nDataBit)))
  })

  val r_done = RegInit(VecInit(Seq.fill(p.nBackPort)(false.B)))

  val w_valid = Wire(Vec(p.nBackPort, Bool()))
  val w_lock = Wire(Vec(p.nBackPort, Bool()))
  val w_wait = Wire(Vec(p.nBackPort, Bool()))
  val w_wait_bp = Wire(Vec(p.nBackPort, Bool()))
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

    w_valid(bp) := io.b_in(bp).valid & ~r_done(bp) & w_back_valid(bp)
  } 

  // ******************************
  //          NEXT BRANCH
  // ******************************
  for (h <- 0 until p.nHart) {
    io.o_br_next(h).valid := false.B
    io.o_br_next(h).addr := DontCare

    for (bp <- p.nBackPort - 1 to 0 by -1) {
      when (w_valid(bp) & (h.U === io.b_in(bp).ctrl.get.info.hart)) {
        io.o_br_next(h).valid := true.B
        io.o_br_next(h).addr := io.b_in(bp).ctrl.get.info.pc
      }
    }
  }  

  // ******************************
  //              END
  // ******************************
  val w_end = Wire(Vec(p.nBackPort, Bool()))

  for (bp <- 0 until p.nBackPort) {
    w_end(bp) := io.i_end(io.b_in(bp).ctrl.get.info.hart)
  }

  for (bp0 <- 0 until p.nBackPort) {
    for (bp1 <- (bp0 + 1) until p.nBackPort) {
      when (w_valid(bp0) & io.b_in(bp0).ctrl.get.info.end & (io.b_in(bp0).ctrl.get.info.hart === io.b_in(bp1).ctrl.get.info.hart)) {
        w_end(bp1) := true.B
      }
    }
  }

  // ******************************
  //        HAS PREDECESSOR
  // ******************************
  val w_has_pred = Wire(Vec(p.nBackPort, Bool()))

  for (bp <- 0 until p.nBackPort) {
    w_has_pred(bp) := false.B
  }

  for (bp0 <- 0 until p.nBackPort) {
    for (bp1 <- (bp0 + 1) until p.nBackPort) {
      when (w_valid(bp0) & (io.b_in(bp0).ctrl.get.info.hart === io.b_in(bp1).ctrl.get.info.hart)) {
        w_has_pred(bp1) := true.B
      }
    }
  }

  // ******************************
  //       WAIT EMPTY PIPELINE
  // ******************************
  val w_wait_empty = Wire(Vec(p.nBackPort, Bool()))

  for (bp <- 0 until p.nBackPort) {
    w_wait_empty(bp) := w_valid(bp) & io.b_in(bp).ctrl.get.info.empty & (io.i_pend(io.b_in(bp).ctrl.get.info.hart) | w_has_pred(bp))
  }

  // ******************************
  //          WAIT BRANCH
  // ******************************
  val w_wait_br = Wire(Vec(p.nBackPort, Bool()))

  for (bp <- 0 until p.nBackPort) {
    w_wait_br(bp) := false.B
  }

  for (bp0 <- 0 until p.nBackPort) {
    if (p.nExStage > 1) {
      for (bp1 <- (bp0 + 1) until p.nBackPort) {
        when (w_valid(bp0) & (io.b_in(bp0).ctrl.get.int.unit === INTUNIT.BRU) & io.b_in(bp1).ctrl.get.lsu.use & (io.b_in(bp0).ctrl.get.info.hart === io.b_in(bp1).ctrl.get.info.hart)) {
          w_wait_br(bp1) := true.B
        }
      }
    }
  }

  // ******************************
  //           DISPATCHER
  // ******************************
  val m_dispatcher = Module(new Dispatcher(p))

  val w_wait_unit = Wire(Vec(p.nBackPort, Bool()))

  m_dispatcher.io.i_free := io.i_free
  for (bp <- 0 until p.nBackPort) {
    m_dispatcher.io.b_port(bp).valid := w_valid(bp)
    m_dispatcher.io.b_port(bp).hart := io.b_in(bp).ctrl.get.info.hart
    m_dispatcher.io.b_port(bp).unit := io.b_in(bp).ctrl.get.int.unit

    w_wait_unit(bp) := ~m_dispatcher.io.b_port(bp).ready & (io.b_in(bp).ctrl.get.int.unit =/= INTUNIT.X)
  }

  // ******************************
  //             DATA
  // ******************************
  // ------------------------------
  //            GPR READ
  // ------------------------------
  for (bp <- 0 until p.nBackPort) {
    io.b_rs(bp * 2).valid := w_valid(bp) & io.b_in(bp).ctrl.get.data.s1_is_reg
    io.b_rs(bp * 2).hart := io.b_in(bp).ctrl.get.info.hart
    io.b_rs(bp * 2).addr := io.b_in(bp).ctrl.get.data.rs1
    io.b_rs(bp * 2 + 1).valid := w_valid(bp) & (io.b_in(bp).ctrl.get.data.s2_is_reg | io.b_in(bp).ctrl.get.data.s3_is_reg)
    io.b_rs(bp * 2 + 1).hart := io.b_in(bp).ctrl.get.info.hart
    io.b_rs(bp * 2 + 1).addr := io.b_in(bp).ctrl.get.data.rs2
  }

  // ------------------------------
  //        SELECT & RESIZE
  // ------------------------------
  val m_s1_size = Seq.fill(p.nBackPort){Module(new SlctSize(p.nDataBit))}
  val m_s2_size = Seq.fill(p.nBackPort){Module(new SlctSize(p.nDataBit))}
  val m_s3_size = Seq.fill(p.nBackPort){Module(new SlctSize(p.nDataBit))}
  
  val w_s1 = Wire(Vec(p.nBackPort, UInt(p.nDataBit.W)))
  val w_s2 = Wire(Vec(p.nBackPort, UInt(p.nDataBit.W)))
  val w_s3 = Wire(Vec(p.nBackPort, UInt(p.nDataBit.W)))

  for (bp <- 0 until p.nBackPort) {
    m_s1_size(bp).io.i_val := Mux(io.b_in(bp).ctrl.get.data.s1_is_reg, io.b_rs(bp * 2).data, io.b_in(bp).ctrl.get.data.s1)
    m_s1_size(bp).io.i_size := io.b_in(bp).ctrl.get.int.ssize(0)
    m_s1_size(bp).io.i_sign := io.b_in(bp).ctrl.get.int.ssign(0)
    w_s1(bp) := m_s1_size(bp).io.o_val

    m_s2_size(bp).io.i_val := Mux(io.b_in(bp).ctrl.get.data.s2_is_reg, io.b_rs(bp * 2 + 1).data, io.b_in(bp).ctrl.get.data.s2)
    m_s2_size(bp).io.i_size := io.b_in(bp).ctrl.get.int.ssize(1)
    m_s2_size(bp).io.i_sign := io.b_in(bp).ctrl.get.int.ssign(1)
    w_s2(bp) := m_s2_size(bp).io.o_val

    m_s3_size(bp).io.i_val := Mux(io.b_in(bp).ctrl.get.data.s2_is_reg, io.b_rs(bp * 2 + 1).data, io.b_in(bp).ctrl.get.data.s3)
    m_s3_size(bp).io.i_size := io.b_in(bp).ctrl.get.int.ssize(2)
    m_s3_size(bp).io.i_sign := io.b_in(bp).ctrl.get.int.ssign(2)
    w_s3(bp) := m_s3_size(bp).io.o_val
  }

  // ******************************
  //          DEPENDENCIES
  // ******************************
  val w_wait_rs = Wire(Vec(p.nBackPort, Bool()))

  // ------------------------------
  //            DEFAULT
  // ------------------------------
  for (bp <- 0 until p.nBackPort) {
    w_wait_rs(bp) := false.B
  }

  // ------------------------------
  //           PIPELINE
  // ------------------------------
  for (bp <- 0 until p.nBackPort) {
    // S1
    when (w_valid(bp) & io.b_in(bp).ctrl.get.data.s1_is_reg & ~io.b_rs(bp * 2).ready) {
      w_wait_rs(bp) := true.B
    }
    // S2
    when (w_valid(bp) & io.b_in(bp).ctrl.get.data.s2_is_reg & ~io.b_rs(bp * 2 + 1).ready) {
      w_wait_rs(bp) := true.B
    }
    // S3
    when (w_valid(bp) & io.b_in(bp).ctrl.get.data.s3_is_reg & ~io.b_rs(bp * 2 + 1).ready) {
      w_wait_rs(bp) := true.B
    }
  }

  // ------------------------------
  //            IN-STAGE
  // ------------------------------
  for (bp0 <- 0 until p.nBackPort) {
    for (bp1 <- (bp0 + 1) until p.nBackPort) {
      when (w_valid(bp0) & io.b_in(bp0).ctrl.get.gpr.en & (io.b_in(bp0).ctrl.get.info.hart === io.b_in(bp1).ctrl.get.info.hart)) {
        when (io.b_in(bp1).ctrl.get.data.s1_is_reg & (io.b_in(bp1).ctrl.get.data.rs1 === io.b_in(bp0).ctrl.get.gpr.addr)) {
          w_wait_rs(bp1) := true.B
        }
        when ((io.b_in(bp1).ctrl.get.data.s2_is_reg | io.b_in(bp1).ctrl.get.data.s3_is_reg) & (io.b_in(bp1).ctrl.get.data.rs2 === io.b_in(bp0).ctrl.get.gpr.addr)) {
          w_wait_rs(bp1) := true.B
        }        
      }
    }
  }

  // ******************************
  //           SERIALIZE
  // ******************************
  val w_wait_ser = Wire(Vec(p.nBackPort, Bool()))

  for (bp <- 0 until p.nBackPort) {
    w_wait_ser(bp) := w_valid(bp) & io.b_in(bp).ctrl.get.info.ser & w_has_pred(bp)
  }

  for (bp0 <- 0 until p.nBackPort) {
    for (bp1 <- (bp0 + 1) until p.nBackPort) {
      when (w_valid(bp0) & io.b_in(bp0).ctrl.get.info.ser & (io.b_in(bp0).ctrl.get.info.hart === io.b_in(bp1).ctrl.get.info.hart)) {
        w_wait_ser(bp1) := true.B
      }
    }
  }  

  // ******************************
  //             FLUSH
  // ******************************
  for (bp <- 0 until p.nBackPort) {
    w_flush(bp) := w_back_flush(bp)
  }

  // ******************************
  //              HPC
  // ******************************
  for (h <- 0 until p.nHart) {
    val w_hpc_srcdep = Wire(Vec(p.nBackPort, Bool()))

    for (bp <- 0 until p.nBackPort) {
      w_hpc_srcdep(bp) := io.b_in(bp).valid & w_wait_rs(bp) & (h.U === io.b_in(bp).ctrl.get.info.hart)
    }

    io.o_hpc_srcdep(h) := PopCount(w_hpc_srcdep.asUInt)
  }  

  // ******************************
  //            OUTPUTS
  // ******************************
  // ------------------------------
  //              BUS
  // ------------------------------
  val m_out = Seq.fill(p.nBackPort){Module(new GenReg(p, new Ex0CtrlBus(p), new DataBus(p.nDataBit), false, false, true))}

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
    w_wait(bp) := ~r_done(bp) & (w_lock(bp) | w_end(bp) | w_wait_unit(bp) | w_wait_rs(bp) | w_wait_empty(bp) | w_wait_br(bp) | w_wait_ser(bp) | w_wait_bp(bp))
  }

  for (bp0 <- 0 until p.nBackPort) {
    for (bp1 <- 0 until bp0) {
      when (io.b_in(bp1).valid & (io.b_in(bp1).ctrl.get.info.hart === io.b_in(bp0).ctrl.get.info.hart) & w_wait(bp1)) {
        w_wait_bp(bp0) := true.B
      }
    }
  }

  // Done
  for (bp <- 0 until p.nBackPort) {
    r_done(bp) := false.B
  }

  for (bp0 <- 0 until p.nBackPort) {
    for (bp1 <- (bp0 + 1) until p.nBackPort) {
      when (io.b_in(bp1).valid & w_wait(bp1) & (io.b_in(bp1).ctrl.get.info.hart === io.b_in(bp0).ctrl.get.info.hart)) {
        r_done(bp0) := ~w_flush(bp0) & (r_done(bp0) | (w_valid(bp0) & ~w_wait(bp0)))
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

    m_out(bp).io.b_in.valid := w_valid(bp) & ~w_flush(bp) & ~w_wait(bp)

    m_out(bp).io.b_in.ctrl.get.info := io.b_in(bp).ctrl.get.info
    m_out(bp).io.b_in.ctrl.get.trap := io.b_in(bp).ctrl.get.trap

    m_out(bp).io.b_in.ctrl.get.int := io.b_in(bp).ctrl.get.int
    m_out(bp).io.b_in.ctrl.get.lsu := io.b_in(bp).ctrl.get.lsu
    m_out(bp).io.b_in.ctrl.get.csr := io.b_in(bp).ctrl.get.csr
    m_out(bp).io.b_in.ctrl.get.gpr := io.b_in(bp).ctrl.get.gpr

    m_out(bp).io.b_in.ctrl.get.ext := io.b_in(bp).ctrl.get.ext

    m_out(bp).io.b_in.ctrl.get.hpc.instret := false.B
    m_out(bp).io.b_in.ctrl.get.hpc.alu := (io.b_in(bp).ctrl.get.int.unit === INTUNIT.ALU)
    m_out(bp).io.b_in.ctrl.get.hpc.ld := io.b_in(bp).ctrl.get.lsu.ld
    m_out(bp).io.b_in.ctrl.get.hpc.st := io.b_in(bp).ctrl.get.lsu.st
    m_out(bp).io.b_in.ctrl.get.hpc.bru := (io.b_in(bp).ctrl.get.int.unit === INTUNIT.BRU)
    m_out(bp).io.b_in.ctrl.get.hpc.mispred := false.B
    m_out(bp).io.b_in.ctrl.get.hpc.rdcycle := io.b_in(bp).ctrl.get.csr.read & (io.b_in(bp).ctrl.get.data.s3(11, 0) === CSR.CYCLE.U) 
    m_out(bp).io.b_in.ctrl.get.hpc.jal := (io.b_in(bp).ctrl.get.int.unit === INTUNIT.BRU) & (io.b_in(bp).ctrl.get.int.uop === INTUOP.JAL)
    m_out(bp).io.b_in.ctrl.get.hpc.jalr := (io.b_in(bp).ctrl.get.int.unit === INTUNIT.BRU) & (io.b_in(bp).ctrl.get.int.uop === INTUOP.JALR)
    m_out(bp).io.b_in.ctrl.get.hpc.cflush := (io.b_in(bp).ctrl.get.int.unit === INTUNIT.BRU) & (io.b_in(bp).ctrl.get.int.uop === INTUOP.FLUSH)
    m_out(bp).io.b_in.ctrl.get.hpc.call := (io.b_in(bp).ctrl.get.int.unit === INTUNIT.BRU) & (io.b_in(bp).ctrl.get.int.uop === INTUOP.JALR) & io.b_in(bp).ctrl.get.int.call
    m_out(bp).io.b_in.ctrl.get.hpc.ret := (io.b_in(bp).ctrl.get.int.unit === INTUNIT.BRU) & (io.b_in(bp).ctrl.get.int.uop === INTUOP.JALR) & io.b_in(bp).ctrl.get.int.ret

    m_out(bp).io.b_in.data.get.s1 := w_s1(bp)
    m_out(bp).io.b_in.data.get.s2 := w_s2(bp)
    m_out(bp).io.b_in.data.get.s3 := w_s3(bp)    

    io.b_out(bp) <> m_out(bp).io.b_out
  }

  // ------------------------------
  //             LOCK
  // ------------------------------
  for (bp <- 0 until p.nBackPort) {
    io.b_in(bp).ready := w_flush(bp) | ~w_wait(bp)
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
  //             DEBUG
  // ******************************
  if (p.debug) {  
    // ------------------------------
    //            SIGNALS
    // ------------------------------
    dontTouch(w_valid)
    dontTouch(w_lock)
    dontTouch(w_wait_bp)
    dontTouch(w_wait)
    dontTouch(r_done)   

    // ------------------------------
    //         DATA FOOTPRINT
    // ------------------------------
    val w_dfp = Wire(Vec(p.nBackPort, new Bundle {
      val pc = UInt(p.nAddrBit.W)
      val instr = UInt(p.nInstrBit.W)
      val wire = Vec(2, UInt(p.nDataBit.W))

      val s1 = UInt(p.nDataBit.W)
      val s2 = UInt(p.nDataBit.W)
      val s3 = UInt(p.nDataBit.W)
    }))

    for (bp <- 0 until p.nBackPort) {
      w_dfp(bp).wire(0) := io.b_rs(bp * 2).data
      w_dfp(bp).wire(1) := io.b_rs(bp * 2 + 1).data
      w_dfp(bp).pc := m_out(bp).io.o_reg.ctrl.get.info.pc
      w_dfp(bp).instr := m_out(bp).io.o_reg.ctrl.get.info.instr
      w_dfp(bp).s1 := m_out(bp).io.o_reg.data.get.s1
      w_dfp(bp).s2 := m_out(bp).io.o_reg.data.get.s2
      w_dfp(bp).s3 := m_out(bp).io.o_reg.data.get.s3 
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

object IssStage extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new IssStage(BackConfigBase), args)
}