/*
 * File: back.scala                                                            *
 * Created Date: 2023-03-08 01:51:25 pm                                        *
 * Author: Mathieu Escouteloup                                                 *
 * -----                                                                       *
 * Last Modified: 2023-04-12 08:52:26 am                                       *
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
import herd.common.mem.mb4s._
import herd.common.mem.cbo._
import herd.core.aubrac.common._
import herd.core.aubrac.nlp.{BranchInfoBus}
import herd.core.aubrac.back.{Gpr, Fsm, BackPortBus, StageBus}
import herd.core.aubrac.back.csr.{Csr, CsrMemIO}
import herd.core.aubrac.hfu.{HfuIO}
import herd.io.core.clint.{ClintIO}


class Back(p: BackParams) extends Module {
  val io = IO(new Bundle {
    val b_field = if (p.useField) Some(Vec(p.nField, new FieldIO(p.nAddrBit, p.nDataBit))) else None
    val b_hart = if (p.useField) Some(Vec(p.nHart, new RsrcIO(p.nHart, p.nField, p.nHart))) else None
    val b_part = if (p.useField) Some(new NRsrcIO(p.nHart, p.nField, p.nPart)) else None

    val i_flush = Input(Vec(p.nHart, Bool()))
    val o_flush = Output(Vec(p.nHart, Bool()))

    val b_in = Vec(p.nBackPort, Flipped(new GenRVIO(p, new BackPortBus(p.debug, p.nHart, p.nAddrBit, p.nInstrBit), UInt(0.W))))

    val i_br_next = Input(Vec(p.nHart, new BranchBus(p.nAddrBit)))
    val o_br_new = Output(Vec(p.nHart, new BranchBus(p.nAddrBit)))
    val o_br_info = Output(Vec(p.nHart, new BranchInfoBus(p.nAddrBit)))

    val b_d0mem = new Mb4sIO(p.pL0D0Bus)
    val b_d1mem = if (p.nLsuPort > 1) Some(new Mb4sIO(p.pL0D1Bus)) else None
    val b_cbo = if (p.useCbo) Some(Vec(p.nHart, new CboIO(p.nHart, p.useField, p.nField, p.nAddrBit))) else None
    val b_clint = Vec(p.nHart, Flipped(new ClintIO(p.nDataBit)))
    
    val b_hfu = if (p.useChamp) Some(Vec(p.nHart, Flipped(new HfuIO(p, p.nAddrBit, p.nDataBit, p.nChampTrapLvl)))) else None  

    val o_hpc = Output(Vec(p.nHart, new HpcPipelineBus()))
    val i_hpm = Input(Vec(p.nHart, Vec(32, UInt(64.W))))
    
    val o_etd = if (p.debug) Some(Output(Vec(p.nCommit, new EtdBus(p.nHart, p.nAddrBit, p.nInstrBit)))) else None
    val o_dbg = if (p.debug) Some(Output(new BackDbgBus(p))) else None
  })

  val m_id = Module(new IdStage(p))
  val m_iss = Module(new IssStage(p))
  val m_ex = Module(new ExStage(p))
  val m_mem = Module(new MemStage(p))
  val m_lsu = Module(new Lsu(p))
  val m_wb = Module(new WbStage(p))

  val m_gpr = Module(new Gpr(p))
  val m_csr = Module(new Csr(p))
  val m_fsm = Seq.fill(p.nHart){Module(new Fsm(p.nHart, p.useChamp, p.nInstrBit, p.nAddrBit, p.nDataBit))}

  // ******************************
  //        RESOURCE STATUS
  // ******************************
  val m_back = if (p.useField) Some(Module(new Part2Rsrc(p.nHart, p.nField, p.nPart, p.nBackPort))) else None
  val m_alu = if (p.useField) Some(Module(new Part2Rsrc(p.nHart, p.nField, p.nPart, p.nAlu))) else None
  val m_muldiv = if (p.useField && (p.nMulDiv > 0)) Some(Module(new Part2Rsrc(p.nHart, p.nField, p.nPart, p.nMulDiv))) else None

  if (p.useField) {
    m_back.get.io.b_part <> io.b_part.get
    m_alu.get.io.b_part <> io.b_part.get
    if (p.nMulDiv > 0) m_muldiv.get.io.b_part <> io.b_part.get
  }

  // ******************************
  //             FSM
  // ******************************  
  // ------------------------------
  //            EMPTY
  // ------------------------------
  val w_act = Wire(Vec(p.nHart, Vec((p.nExStage + 4) , Vec(p.nBackPort, Bool()))))
  val w_empty = Wire(Vec(p.nHart, Bool()))

  for (h <- 0 until p.nHart) { 
    for (bp <- 0 until p.nBackPort) {
      w_act(h)(0)(bp) := m_id.io.o_stage(bp).valid & (h.U === m_id.io.o_stage(bp).hart)
      w_act(h)(1)(bp) := m_iss.io.o_stage(bp).valid & (h.U === m_iss.io.o_stage(bp).hart)
      for (e <- 0 until p.nExStage) {
        w_act(h)(2 + e)(bp) := m_ex.io.o_stage(e)(bp).valid & (h.U === m_ex.io.o_stage(e)(bp).hart)
      }
      w_act(h)(2 + p.nExStage)(bp) := m_mem.io.o_stage(bp).valid & (h.U === m_mem.io.o_stage(bp).hart)
      w_act(h)(3 + p.nExStage)(bp) := m_wb.io.o_stage(bp).valid & (h.U === m_wb.io.o_stage(bp).hart)      
    } 

    w_empty(h) := ~w_act(h).asUInt.orR
  }

  // ------------------------------
  //            MODULE
  // ------------------------------
  for (h <- 0 until p.nHart) {
    if (p.useField) m_fsm(h).io.b_hart.get <> io.b_hart.get(h)
    m_fsm(h).io.i_stop := m_id.io.o_stop(h) | m_lsu.io.o_stop(h) | m_wb.io.o_stop(h)
    m_fsm(h).io.i_empty := w_empty(h)
    m_fsm(h).io.i_br := m_ex.io.o_br_new(h).valid
    m_fsm(h).io.i_wb := m_wb.io.o_last(h)
    m_fsm(h).io.i_raise := m_wb.io.o_raise(h)
    m_fsm(h).io.b_clint <> io.b_clint(h) 
  }

  // ******************************
  //            ID STAGE
  // ******************************
  if (p.useField) {
    m_id.io.b_hart.get <> io.b_hart.get
    m_id.io.b_back.get <> m_back.get.io.b_rsrc.state
  }
  for (h <- 0 until p.nHart) {
    m_id.io.i_flush(h) := m_fsm(h).io.o_trap.valid | io.i_flush(h) | m_ex.io.o_flush(h)
  }  
  m_id.io.b_in <> io.b_in
  for (bp <- 0 until p.nBackPort) {
    m_id.io.b_in(bp).valid := false.B
    io.b_in(bp).ready := false.B 

    for (h <- 0 until p.nHart) {
      when (h.U === io.b_in(bp).ctrl.get.hart) {
        m_id.io.b_in(bp).valid := io.b_in(bp).valid & ~m_fsm(h).io.o_lock
        io.b_in(bp).ready := m_id.io.b_in(bp).ready & ~m_fsm(h).io.o_lock
      }
    }
  }
  m_id.io.i_csr := m_csr.io.o_decoder

  // ******************************
  //           ISS STAGE
  // ******************************
  // ------------------------------
  //           END & PEND
  // ------------------------------
  val w_pend = Wire(Vec(p.nHart, Vec((p.nExStage + 2), Bool())))
  val w_end = Wire(Vec(p.nHart, Vec((p.nExStage + 2), Bool())))

  for (h <- 0 until p.nHart) {
    w_pend(h) := 0.U.asTypeOf(w_pend(h))
    w_end(h) := 0.U.asTypeOf(w_end(h))

    for (bp <- 0 until p.nBackPort) {
      when (m_wb.io.o_stage(bp).valid & (h.U === m_wb.io.o_stage(bp).hart)) {
        w_pend(h)(0) := true.B
        when (m_wb.io.o_stage(bp).end) {
          w_end(h)(0) := true.B
        }
      }
      when (m_mem.io.o_stage(bp).valid & (h.U === m_mem.io.o_stage(bp).hart)) {
        w_pend(h)(1) := true.B
        when (m_mem.io.o_stage(bp).end) {
          w_end(h)(1) := true.B
        }
      }
      for (e <- 0 until p.nExStage) {
        when (m_ex.io.o_stage(e)(bp).valid & (h.U === m_ex.io.o_stage(e)(bp).hart)) {
          w_pend(h)(2 + e) := true.B
          when (m_ex.io.o_stage(e)(bp).end) {
            w_end(h)(2 + e) := true.B
          }
        }
      }
    }
  }

  // ------------------------------
  //            MODULE
  // ------------------------------
  if (p.useField) {
    m_iss.io.b_hart.get <> io.b_hart.get
    m_iss.io.b_back.get <> m_back.get.io.b_rsrc.state
  }
  for (h <- 0 until p.nHart) {
    m_iss.io.i_flush(h) := m_fsm(h).io.o_trap.valid | io.i_flush(h) | m_ex.io.o_flush(h)
  }  
  m_iss.io.b_in <> m_id.io.b_out
  for (h <- 0 until p.nHart) {
    m_iss.io.i_pend(h) := w_pend(h).asUInt.orR
    m_iss.io.i_end(h) := w_end(h).asUInt.orR
  }
  m_iss.io.i_free := m_ex.io.o_free
  
  // ******************************
  //         GPR AND BYPASS
  // ******************************
  m_gpr.io.b_read <> m_iss.io.b_rs
  m_gpr.io.b_write <> m_wb.io.b_rd  

  for (bp <- 0 until p.nBackPort) {
    m_gpr.io.i_byp(bp) := m_wb.io.o_byp(bp)
    m_gpr.io.i_byp(p.nBackPort + bp) := m_mem.io.o_byp(bp)
  }
  for (ebp <- 0 until (p.nExStage * p.nBackPort)) {
    m_gpr.io.i_byp(2 * p.nBackPort + ebp) := m_ex.io.o_byp(ebp)
  }

  // ******************************
  //            EX STAGE
  // ******************************
  // ------------------------------
  //       NEXT CURRENT BRANCH
  // ------------------------------
  val w_br_next = Wire(Vec(p.nHart, new BranchBus(p.nAddrBit)))

  for (h <- 0 until p.nHart) {
    w_br_next(h) := io.i_br_next(h)
    when (m_iss.io.o_br_next(h).valid) {
      w_br_next(h) := m_iss.io.o_br_next(h)
    }
  }

  // ------------------------------
  //            MODULE
  // ------------------------------
  if (p.useField) {
    m_ex.io.b_hart.get <> io.b_hart.get
    m_ex.io.b_back.get <> m_back.get.io.b_rsrc.state
    m_ex.io.b_alu.get <> m_alu.get.io.b_rsrc.state
    if (p.nMulDiv > 0) m_ex.io.b_muldiv.get <> m_muldiv.get.io.b_rsrc.state
  }
  for (h <- 0 until p.nHart) {
    m_ex.io.i_flush(h) := m_fsm(h).io.o_trap.valid | io.i_flush(h)
  }  
  m_ex.io.b_in <> m_iss.io.b_out
  m_ex.io.i_br_next := w_br_next
  if (p.useCbo) m_ex.io.b_cbo.get <> io.b_cbo.get 
  for (h <- 0 until p.nHart) {
    m_ex.io.i_exc_on(h) := false.B
    for (bp <- 0 until p.nBackPort) {
      when (m_mem.io.o_stage(bp).valid & (h.U === m_mem.io.o_stage(bp).hart) & m_mem.io.o_stage(bp).exc_gen) {
        m_ex.io.i_exc_on(h) := true.B
      }
      when (m_wb.io.o_stage(bp).valid & (h.U === m_wb.io.o_stage(bp).hart) & m_wb.io.o_stage(bp).exc_gen) {
        m_ex.io.i_exc_on(h) := true.B
      }
    }
  }    

  // ******************************
  //              LSU 
  // ******************************
  if (p.useField) {
    m_lsu.io.b_hart.get <> io.b_hart.get
  }
  for (h <- 0 until p.nHart) {
    m_lsu.io.i_flush(h) := m_fsm(h).io.o_trap.valid | io.i_flush(h)
  }  
  m_lsu.io.b_req <> m_ex.io.b_lsu
  m_lsu.io.b_d0mem <> io.b_d0mem
  if (p.nHart > 1) m_lsu.io.b_d1mem.get <> io.b_d1mem.get
  m_lsu.io.b_wb <> m_wb.io.b_lsu

  // ******************************
  //           MEM STAGE
  // ******************************
  if (p.useField) {
    m_mem.io.b_hart.get <> io.b_hart.get
    m_mem.io.b_back.get <> m_back.get.io.b_rsrc.state
  }
  for (h <- 0 until p.nHart) {
    m_mem.io.i_flush(h) := m_fsm(h).io.o_trap.valid | io.i_flush(h)
  }  
  m_mem.io.b_in <> m_ex.io.b_out

  // ******************************
  //              CSR
  // ******************************
  if (p.useField) {
    m_csr.io.b_field.get <> io.b_field.get
    m_csr.io.b_hart.get <> io.b_hart.get
  }
  m_csr.io.b_read <> m_mem.io.b_csr
  m_csr.io.b_write <> m_wb.io.b_csr

  m_csr.io.i_hpm := io.i_hpm
  
  m_csr.io.b_clint <> io.b_clint
  for (h <- 0 until p.nHart) {
    m_csr.io.i_trap(h) := m_fsm(h).io.o_trap
  }

  // ******************************
  //            WB STAGE
  // ******************************
  if (p.useField) {
    m_wb.io.b_back.get <> m_back.get.io.b_rsrc.state
  }
  for (h <- 0 until p.nHart) {
    m_wb.io.i_flush(h) := m_fsm(h).io.o_trap.valid | io.i_flush(h)
  }  
  m_wb.io.b_in <> m_mem.io.b_out

  // ******************************
  //              HFU
  // ******************************
  if (p.useChamp) {
    for (h <- 0 until p.nHart) {
      io.b_hfu.get(h).ctrl.hfu_flush := m_fsm(h).io.o_trap.valid
      when (m_csr.io.b_trap.get(h).valid) {
        io.b_hfu.get(h).req <> m_csr.io.b_trap.get(h)
        m_ex.io.b_hfu.get(h).ready := false.B
      }.otherwise {
        io.b_hfu.get(h).req <> m_ex.io.b_hfu.get(h)
        m_csr.io.b_trap.get(h).ready := false.B
      }
      m_wb.io.b_hfu.get(h) <> io.b_hfu.get(h).ack
      m_csr.io.b_hfu.get(h) <> io.b_hfu.get(h).csr
    }
  }
  
  // ******************************
  //            WB STAGE
  // ******************************
  for (h <- 0 until p.nHart) {
    m_wb.io.i_flush(h) := m_fsm(h).io.o_trap.valid | io.i_flush(h)
  }
  m_wb.io.b_in <> m_mem.io.b_out
  m_wb.io.b_lsu <> m_lsu.io.b_wb

  // ******************************
  //              I/O
  // ******************************
  io.o_hpc := m_wb.io.o_hpc
  for (h <- 0 until p.nHart) {
    for (bp <- 0 until p.nBackPort) {
      io.o_hpc(h).srcdep(bp) := m_iss.io.o_hpc_srcdep(h)(bp)
    }
  }
  
  // ------------------------------
  //             BRANCH
  // ------------------------------
  for (h <- 0 until p.nHart) {
    io.o_br_new(h) := Mux(m_csr.io.o_br_trap(h).valid, m_csr.io.o_br_trap(h), m_ex.io.o_br_new(h))
    io.o_br_info(h) := m_ex.io.o_br_info(h)
  }

  // ------------------------------
  //             FLUSH
  // ------------------------------
  for (h <- 0 until p.nHart) {
    io.o_flush(h) := m_fsm(h).io.o_trap.valid | m_id.io.o_flush(h) | m_ex.io.o_flush(h)
  }

  // ******************************
  //             FIELD
  // ******************************
  if (p.useField) {
    for (h <- 0 until p.nHart) {
      io.b_hart.get(h).free := m_id.io.b_hart.get(h).free & m_iss.io.b_hart.get(h).free & m_ex.io.b_hart.get(h).free & m_mem.io.b_hart.get(h).free & m_lsu.io.b_hart.get(h).free & m_fsm(h).io.b_hart.get.free
    }    
    for (bp <- 0 until p.nBackPort) {
      m_back.get.io.b_rsrc.state(bp).free := m_id.io.b_back.get(bp).free & m_iss.io.b_back.get(bp).free & m_ex.io.b_back.get(bp).free & m_mem.io.b_back.get(bp).free & m_wb.io.b_back.get(bp).free
    }
  }

  // ******************************
  //             DEBUG
  // ******************************
  if (p.debug) {
    // ------------------------------
    //            SIGNALS
    // ------------------------------
    val r_dbg_last = Reg(Vec(p.nHart, UInt(p.nAddrBit.W)))

    for (h <- 0 until p.nHart) {    
      when (m_wb.io.o_last(h).valid) {
        r_dbg_last(h) := m_wb.io.o_last(h).pc
      }

      io.o_dbg.get.last(h) := r_dbg_last(h)
      io.o_dbg.get.x(h) := m_gpr.io.o_dbg.get(h)
      io.o_dbg.get.csr(h) := m_csr.io.o_dbg.get(h)
    }

    // ------------------------------
    //       EXECUTION TRACKER
    // ------------------------------
    // Default
    val init_etd = Wire(Vec(p.nCommit, new EtdBus(p.nHart, p.nAddrBit, p.nInstrBit)))

    for (c <- 0 until p.nCommit) {   
      init_etd(c) := DontCare
      init_etd(c).done := false.B
    }

    val r_etd = RegInit(init_etd)
    val r_time = RegInit(0.U(64.W))
    
    // Registers
    for (c <- 0 until p.nCommit) {   
      r_etd(c).done := false.B
      when (m_wb.io.o_etd.get(c).done) {
        r_etd(c) := m_wb.io.o_etd.get(c)
        r_etd(c).tend := r_time + 1.U
      }
    }

    // Time update
    r_time := r_time + 1.U

    // Output
    io.o_etd.get := r_etd
  }
}

object Back extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new Back(BackConfigBase), args)
}