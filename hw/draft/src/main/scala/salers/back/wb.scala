/*
 * File: wb.scala                                                              *
 * Created Date: 2023-03-08 01:51:25 pm                                        *
 * Author: Mathieu Escouteloup                                                 *
 * -----                                                                       *
 * Last Modified: 2023-04-11 05:43:33 pm                                       *
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
import herd.common.core.{HpcPipelineBus}
import herd.common.isa.priv.{EXC => PRIVEXC}
import herd.common.isa.champ.{EXC => CHAMPEXC}
import herd.core.aubrac.common._
import herd.core.aubrac.back.{BypassBus,ResultBus,GprWriteIO,RaiseBus,StageBus}
import herd.core.aubrac.back.{EXT}
import herd.core.aubrac.back.csr.{CsrWriteIO}
import herd.core.aubrac.hfu.{HfuAckIO}


class WbStage(p: BackParams) extends Module {
  val io = IO(new Bundle {
    val b_back = if (p.useField) Some(Vec(p.nBackPort, new RsrcIO(p.nHart, p.nField, p.nBackPort))) else None
    
    val i_flush = Input(Vec(p.nHart, Bool()))    

    val b_in = Vec(p.nBackPort, Flipped(new GenRVIO(p, new WbCtrlBus(p), new ResultBus(p.nDataBit))))

    val o_stop = Output(Vec(p.nHart, Bool()))
    val o_raise = Output(Vec(p.nHart, new RaiseBus(p.nAddrBit, p.nDataBit)))
    val o_stage = Output(Vec(p.nBackPort, new StageBus(p.nHart, p.nAddrBit, p.nInstrBit)))

    val b_lsu = Vec(p.nHart, Vec(p.nBackPort, Flipped(new GenRVIO(p, new LsuAckCtrlBus(p), UInt(p.nDataBit.W)))))
    val b_csr = Vec(p.nHart, Flipped(new CsrWriteIO(p.nDataBit)))    
    val o_byp = Output(Vec(p.nBackPort, new BypassBus(p.nHart, p.nDataBit)))
    val b_rd = Vec(p.nBackPort, Flipped(new GprWriteIO(p)))

    val b_hfu = if (p.useChamp) Some(Vec(p.nHart, Flipped(new HfuAckIO(p, p.nAddrBit, p.nDataBit)))) else None

    val o_hpc = Output(Vec(p.nHart, new HpcPipelineBus()))
    val o_last = Output(Vec(p.nHart, new StageBus(p.nHart, p.nAddrBit, p.nInstrBit)))
    
    val o_etd = if (p.debug) Some(Output(Vec(p.nCommit, new EtdBus(p.nHart, p.nAddrBit, p.nInstrBit)))) else None
  })

  val w_wait_lsu = Wire(Vec(p.nBackPort, Bool()))
  val w_wait_hfu = Wire(Vec(p.nBackPort, Bool()))
  val w_wait_gpr = Wire(Vec(p.nBackPort, Bool()))
  val w_wait_bp = Wire(Vec(p.nBackPort, Bool()))
  val w_wait = Wire(Vec(p.nBackPort, Bool()))
  val w_flush = Wire(Vec(p.nBackPort, Bool()))

  val w_trap = Wire(Vec(p.nBackPort, new TrapBus(p.nAddrBit, p.nDataBit)))
  val w_res = Wire(Vec(p.nBackPort, UInt(p.nDataBit.W)))

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
  //              HFU
  // ******************************
  if (p.useChamp) {
    for (h <- 0 until p.nHart) {
      io.b_hfu.get(h) := DontCare
      io.b_hfu.get(h).ready := false.B
    }
  }

  // ******************************
  //              CSR
  // ******************************
  for (h <- 0 until p.nHart) {
    io.b_csr(h) := DontCare
    io.b_csr(h).valid := false.B

    for (bp <- 0 until p.nBackPort) {
      when (io.b_in(bp).valid & io.b_in(bp).ctrl.get.csr.write & (h.U === io.b_in(bp).ctrl.get.info.hart)) {
        io.b_csr(h).valid := true.B
        io.b_csr(h).addr := io.b_in(bp).data.get.s3(11,0)
        io.b_csr(h).uop := io.b_in(bp).ctrl.get.csr.uop
        io.b_csr(h).data := io.b_in(bp).data.get.res
        io.b_csr(h).mask := io.b_in(bp).data.get.s1
      }
    }
  }

  // ******************************
  //             RESULT
  // ******************************
  for (bp <- 0 until p.nBackPort) {
    // ------------------------------
    //            DEFAULT
    // ------------------------------
    w_trap(bp) := io.b_in(bp).ctrl.get.trap
    w_res(bp) := io.b_in(bp).data.get.res

    // ------------------------------
    //             LSU
    // ------------------------------
    for (h <- 0 until p.nHart) {
      io.b_lsu(h)(bp).ready := false.B
    }

    w_wait_lsu(bp) := io.b_in(bp).ctrl.get.lsu.use & ~io.b_lsu(io.b_in(bp).ctrl.get.info.hart)(bp).valid
    io.b_lsu(io.b_in(bp).ctrl.get.info.hart)(bp).ready := io.b_in(bp).valid & io.b_in(bp).ctrl.get.lsu.use & ~w_wait_bp(bp)
    when (io.b_in(bp).valid & io.b_in(bp).ctrl.get.lsu.load) {
      w_trap(bp) := io.b_lsu(io.b_in(bp).ctrl.get.info.hart)(bp).ctrl.get.trap
      w_res(bp) := io.b_lsu(io.b_in(bp).ctrl.get.info.hart)(bp).data.get
    }

    // ------------------------------
    //             HFU
    // ------------------------------
    w_wait_hfu(bp) := false.B

    if (p.useChamp) {
      for (h <- 0 until p.nHart) {
        when (io.b_in(bp).valid & (io.b_in(bp).ctrl.get.ext.ext === EXT.HFU) & (h.U === io.b_in(bp).ctrl.get.info.hart) & ~w_wait_bp(bp)) {
          w_wait_hfu(bp) := ~io.b_hfu.get(h).valid
          io.b_hfu.get(h).ready := true.B
          w_res(bp) := io.b_hfu.get(h).data.get
        }
      }
    }
  }

  // ******************************
  //             BYPASS
  // ******************************
  for (bp <- 0 until p.nBackPort) {
    io.o_byp(bp).valid := io.b_in(bp).valid & io.b_in(bp).ctrl.get.gpr.en
    io.o_byp(bp).hart := io.b_in(bp).ctrl.get.info.hart
    io.o_byp(bp).ready := (~io.b_in(bp).ctrl.get.lsu.load | ~w_wait_lsu(bp)) & ~((io.b_in(bp).ctrl.get.ext.ext === EXT.HFU) | ~w_wait_hfu(bp))
    io.o_byp(bp).addr := io.b_in(bp).ctrl.get.gpr.addr
    io.o_byp(bp).data := w_res(bp)
  }

  // ******************************
  //           WRITE GPR
  // ******************************
  for (bp <- 0 until p.nBackPort) {
    w_wait_gpr(bp) := io.b_in(bp).ctrl.get.gpr.en & ~io.b_rd(bp).ready
    
    io.b_rd(bp).valid := io.b_in(bp).valid & io.b_in(bp).ctrl.get.gpr.en & ~w_wait(bp) & ~w_trap(bp).valid
    io.b_rd(bp).hart := io.b_in(bp).ctrl.get.info.hart
    io.b_rd(bp).addr := io.b_in(bp).ctrl.get.gpr.addr
    io.b_rd(bp).data := w_res(bp)
  }

  // ******************************
  //             TRAP
  // ******************************
  for (h <- 0 until p.nHart) {
    io.o_raise(h) := DontCare
    io.o_raise(h).valid := false.B
    for (bp <- 0 until p.nBackPort) {
      when (io.b_in(bp).valid & ~w_flush(bp) & ~w_wait(bp) & w_trap(bp).valid) {
        io.o_raise(h).valid := true.B
        io.o_raise(h).pc := io.b_in(bp).ctrl.get.info.pc
        io.o_raise(h).src := io.b_in(bp).ctrl.get.trap.src
        io.o_raise(h).cause := io.b_in(bp).ctrl.get.trap.cause
        io.o_raise(h).info := DontCare
        if (p.useChamp) {
          switch (io.b_in(bp).ctrl.get.trap.cause) {
            is (CHAMPEXC.IINSTR.U)  {io.o_raise(h).info := io.b_in(bp).ctrl.get.info.instr}
          }
        } else {
          switch (io.b_in(bp).ctrl.get.trap.cause) {
            is (PRIVEXC.IINSTR.U)   {io.o_raise(h).info := io.b_in(bp).ctrl.get.info.instr}
          }
        }
      }
    }

    io.o_stop(h) := false.B 
    for (bp <- 0 until p.nBackPort) {
      when (io.b_in(bp).valid & (h.U === io.b_in(bp).ctrl.get.info.hart) & ~w_wait(bp) & ~w_flush(bp) & w_trap(bp).valid) {
        io.o_stop(h) := true.B
      }
    }
  }

  // ******************************
  //             WAIT
  // ******************************
  for (bp <- 0 until p.nBackPort) {
    w_wait_bp(bp) := false.B
    w_wait(bp) := (w_wait_gpr(bp) | w_wait_lsu(bp) | w_wait_hfu(bp) | w_wait_bp(bp))

    io.b_in(bp).ready := w_flush(bp) | ~w_wait(bp)
  }

  for (bp0 <- 0 until p.nBackPort) {
    for (bp1 <- 0 until bp0) {
      when (io.b_in(bp1).valid & (io.b_in(bp1).ctrl.get.info.hart === io.b_in(bp0).ctrl.get.info.hart) & w_wait(bp1)) {
        w_wait_bp(bp0) := true.B
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
  //              HPC
  // ******************************
  io.o_hpc := 0.U.asTypeOf(io.o_hpc)
  for (h <- 0 until p.nHart) {
    for (bp <- 0 until p.nBackPort) {
      io.o_hpc(h).instret(bp) := io.b_in(bp).valid & (h.U === io.b_in(bp).ctrl.get.info.hart) & ~w_flush(bp) & ~w_wait(bp)
      io.o_hpc(h).alu(bp) := io.b_in(bp).valid & (h.U === io.b_in(bp).ctrl.get.info.hart) & ~w_flush(bp) & ~w_wait(bp) & io.b_in(bp).ctrl.get.hpc.alu
      io.o_hpc(h).ld(bp) := io.b_in(bp).valid & (h.U === io.b_in(bp).ctrl.get.info.hart) & ~w_flush(bp) & ~w_wait(bp) & io.b_in(bp).ctrl.get.hpc.ld
      io.o_hpc(h).st(bp) := io.b_in(bp).valid & (h.U === io.b_in(bp).ctrl.get.info.hart) & ~w_flush(bp) & ~w_wait(bp) & io.b_in(bp).ctrl.get.hpc.st
      io.o_hpc(h).bru(bp) := io.b_in(bp).valid & (h.U === io.b_in(bp).ctrl.get.info.hart) & ~w_flush(bp) & ~w_wait(bp) & io.b_in(bp).ctrl.get.hpc.bru
      io.o_hpc(h).mispred(bp) := io.b_in(bp).valid & (h.U === io.b_in(bp).ctrl.get.info.hart) & ~w_flush(bp) & ~w_wait(bp) & io.b_in(bp).ctrl.get.hpc.mispred
      io.o_hpc(h).rdcycle(bp) := io.b_in(bp).valid & (h.U === io.b_in(bp).ctrl.get.info.hart) & ~w_flush(bp) & ~w_wait(bp) & io.b_in(bp).ctrl.get.hpc.rdcycle
      io.o_hpc(h).call(bp) := io.b_in(bp).valid & (h.U === io.b_in(bp).ctrl.get.info.hart) & ~w_flush(bp) & ~w_wait(bp) & io.b_in(bp).ctrl.get.hpc.call
      io.o_hpc(h).ret(bp) := io.b_in(bp).valid & (h.U === io.b_in(bp).ctrl.get.info.hart) & ~w_flush(bp) & ~w_wait(bp) & io.b_in(bp).ctrl.get.hpc.ret
      io.o_hpc(h).jal(bp) := io.b_in(bp).valid & (h.U === io.b_in(bp).ctrl.get.info.hart) & ~w_flush(bp) & ~w_wait(bp) & io.b_in(bp).ctrl.get.hpc.jal
      io.o_hpc(h).jalr(bp) := io.b_in(bp).valid & (h.U === io.b_in(bp).ctrl.get.info.hart) & ~w_flush(bp) & ~w_wait(bp) & io.b_in(bp).ctrl.get.hpc.jalr
      io.o_hpc(h).cflush(bp) := io.b_in(bp).valid & (h.U === io.b_in(bp).ctrl.get.info.hart) & ~w_flush(bp) & ~w_wait(bp) & io.b_in(bp).ctrl.get.hpc.cflush
    }
  }

  // ******************************
  //             LAST
  // ******************************
  for (h <- 0 until p.nHart) {  
    io.o_last(h) := DontCare
    io.o_last(h).valid := false.B
    for (bp <- 0 until p.nBackPort) {
      when (io.b_in(bp).valid & (h.U === io.b_in(bp).ctrl.get.info.hart) & ~w_flush(bp) & ~w_wait(bp)) {
        io.o_last(h).valid := true.B
        io.o_last(h).hart := h.U
        io.o_last(h).pc := io.b_in(bp).ctrl.get.info.pc
        io.o_last(h).instr := io.b_in(bp).ctrl.get.info.instr
        io.o_last(h).exc_gen := io.b_in(bp).ctrl.get.trap.gen
        io.o_last(h).end := io.b_in(bp).ctrl.get.info.end
      }
    }
  }

  // ******************************
  //             FIELD
  // ******************************
  if (p.useField) {
    for (bp <- 0 until p.nBackPort) {
      io.b_back.get(bp).free := true.B
    }    
  }  

  // ******************************
  //            DEBUG
  // ******************************
  if (p.debug) {
    // ------------------------------
    //            SIGNALS
    // ------------------------------
    dontTouch(io.b_in)
    
    // ------------------------------
    //         DATA FOOTPRINT
    // ------------------------------

    // ------------------------------
    //       EXECUTION TRACKER
    // ------------------------------
    for (c <- 0 until p.nCommit) {
      io.o_etd.get(c) := io.b_in(c).ctrl.get.etd.get
      io.o_etd.get(c).done := io.b_in(c).valid & ~w_wait(c) & ~w_flush(c)
    }
  }
}

object WbStage extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new WbStage(BackConfigBase), args)
}