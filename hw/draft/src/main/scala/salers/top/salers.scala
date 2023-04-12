/*
 * File: salers.scala                                                          *
 * Created Date: 2023-03-08 01:51:25 pm                                        *
 * Author: Mathieu Escouteloup                                                 *
 * -----                                                                       *
 * Last Modified: 2023-04-12 10:09:37 am                                       *
 * Modified By: Mathieu Escouteloup                                            *
 * -----                                                                       *
 * License: See LICENSE.md                                                     *
 * Copyright (c) 2023 HerdWare                                                 *
 * -----                                                                       *
 * Description:                                                                *
 */


package herd.core.salers

import chisel3._
import chisel3.util._

import herd.common.mem.mb4s._
import herd.common.mem.cbo._
import herd.common.field._
import herd.core.aubrac.common._
import herd.core.aubrac.hfu._
import herd.io.core._
import herd.mem.hay._


class Salers (p: SalersParams) extends Module {
  val io = IO(new Bundle {
    val b_field = if (p.useField) Some(Flipped(Vec(p.nField, new FieldIO(p.nAddrBit, p.nDataBit)))) else None
    val b_pall = if (p.useField) Some(Flipped(new NRsrcIO(p.nHart, p.nField, p.nPart))) else None

    val b_imem = if (!p.useL2) Some(new Mb4sIO(p.pLLIBus)) else None
    val b_dmem = if (!p.useL2) Some(new Mb4sIO(p.pLLDBus)) else None
    val b_mem = if (p.useL2) Some(new Mb4sIO(p.pLLDBus)) else None

    val i_irq_lei = if (p.useChamp) Some(Input(Vec(p.nChampTrapLvl, Bool()))) else None
    val i_irq_lsi = if (p.useChamp) Some(Input(Vec(p.nChampTrapLvl, Bool()))) else None
    val i_irq_mei = if (!p.useChamp) Some(Input(Bool())) else None
    val i_irq_msi = if (!p.useChamp) Some(Input(Bool())) else None

    val o_dbg = if (p.debug) Some(Output(new SalersDbgBus(p))) else None
    val o_etd = if (p.debug) Some(Output(Vec(p.nCommit, new EtdBus(p.nHart, p.nAddrBit, p.nInstrBit)))) else None
  })

  // ******************************
  //            MODULES
  // ******************************
  val m_pipe = Module(new Pipeline(p))
  val m_hfu = if (p.useChamp) Some(Module(new Hfu(p.pHfu))) else None
  val m_pall = if (p.useField) Some(Module(new StaticSlct(p.nField, p.nPart, 1))) else None
  val m_io = Module(new IOCore(p.pIO))
  val m_l0dcross = Module(new Mb4sCrossbar(p.pL0DCross))
  val m_l1i = if (p.useL1I) Some(Module(new Hay(p.pL1I))) else None
  val m_l1d = if (p.useL1D) Some(Module(new Hay(p.pL1D))) else None
  val m_l2 = if (p.useL2) Some(Module(new Hay(p.pL2))) else None
  val m_llcross = if (p.useL1D || p.useL2) Some(Module(new Mb4sCrossbar(p.pLLCross))) else None
  
  // ******************************
  //           PIPELINE
  // ******************************
  if (p.useField) {
    m_pipe.io.b_field.get <> m_hfu.get.io.b_field 
    m_pipe.io.b_hart.get <> m_hfu.get.io.b_hart
    m_pipe.io.b_part.get <> m_hfu.get.io.b_pexe
  }

  m_pipe.io.b_clint <> m_io.io.b_clint
  m_pipe.io.i_hpm := m_io.io.o_hpm(0)

  // ******************************
  //          FIELD SELECT
  // ******************************
  if (p.useField) { 
    for (f <- 0 until p.nField) {
      m_pall.get.io.i_weight(f) := m_hfu.get.io.b_pall.weight(f)
    }

    if (p.useL1I && p.pL1I.multiField) {
      m_l1i.get.io.i_slct_prev.get := m_pall.get.io.o_slct
      if (p.useL2) m_l2.get.io.i_slct_prev.get := m_l1i.get.io.o_slct_next.get
    }

    if (p.useL1D && p.pL1D.multiField) {
      m_l1d.get.io.i_slct_prev.get := m_pall.get.io.o_slct
      if (p.useL2) m_l2.get.io.i_slct_prev.get := m_l1d.get.io.o_slct_next.get
    }

    if (p.useL2 && p.pL2.multiField && (!p.useL1I || !p.pL1I.multiField) && (!p.useL1D || !p.pL1D.multiField)) {
      m_l2.get.io.i_slct_prev.get := m_pall.get.io.o_slct
    }
  }

  // ******************************
  //             MEMORY
  // ******************************
  // ------------------------------
  //              L1I
  // ------------------------------
  val w_l1i_cbo = Wire(Vec(2, Bool()))  

  w_l1i_cbo(0) := false.B
  w_l1i_cbo(1) := true.B

  if (p.useL1I) {
    if (p.useField) {
      m_l1i.get.io.b_field.get <> m_hfu.get.io.b_field
      m_l1i.get.io.b_part.get <> m_hfu.get.io.b_pall
    }
    if (p.useCbo) {
      w_l1i_cbo(0) := m_pipe.io.b_cbo.get.instr
      w_l1i_cbo(1) := ~w_l1i_cbo(0) | m_l1i.get.io.b_cbo(0).ready
      m_l1i.get.io.b_cbo(0) <> m_pipe.io.b_cbo.get
      m_l1i.get.io.b_cbo(0).valid := m_pipe.io.b_cbo.get.valid & w_l1i_cbo(0)
    }

    if (p.useField) m_l1i.get.io.i_slct_prev.get := m_pall.get.io.o_slct
    m_l1i.get.io.b_prev(0) <> m_pipe.io.b_imem
    if (!p.useL2) m_l1i.get.io.b_next <> io.b_imem.get
  } else {    
    m_pipe.io.b_imem <> io.b_imem.get
  }

  // ------------------------------
  //              L0D
  // ------------------------------
  if (p.useField) m_l0dcross.io.b_field.get <> m_hfu.get.io.b_field
  m_l0dcross.io.b_m(0) <> m_pipe.io.b_dmem
  if (p.useChamp) m_l0dcross.io.b_m(1) <> m_hfu.get.io.b_dmem
  m_l0dcross.io.b_s(0) <> m_io.io.b_port 
  if (!p.useL1D && !p.useL2) m_l0dcross.io.b_s(1) <> io.b_dmem.get   

  // ------------------------------
  //              L1D
  // ------------------------------
  val w_l1d_cbo = Wire(Vec(2, Bool())) 

  w_l1d_cbo(0) := false.B
  w_l1d_cbo(1) := true.B

  if (p.useL1D) {
    if (p.useField) {
      m_l1d.get.io.b_field.get <> m_hfu.get.io.b_field
      m_l1d.get.io.b_part.get <> m_hfu.get.io.b_pall
    } 
    if (p.useCbo) {
      w_l1d_cbo(0) := m_pipe.io.b_cbo.get.data
      w_l1d_cbo(1) := ~w_l1d_cbo(0) | m_l1d.get.io.b_cbo(0).ready
      m_l1d.get.io.b_cbo(0) <> m_pipe.io.b_cbo.get
      m_l1d.get.io.b_cbo(0).valid := m_pipe.io.b_cbo.get.valid & w_l1d_cbo(0)
    }

    if (p.useField) m_l1d.get.io.i_slct_prev.get := m_pall.get.io.o_slct
    m_l1d.get.io.b_prev(0) <> m_l0dcross.io.b_s(2)
    if (!p.useL2) {
      if (p.useField) {
        m_llcross.get.io.b_field.get <> m_hfu.get.io.b_field
        m_llcross.get.io.i_slct_req.get := m_l1d.get.io.o_slct_next.get
        m_llcross.get.io.i_slct_read.get := m_l1d.get.io.o_slct_prev.get
        m_llcross.get.io.i_slct_write.get := m_l1d.get.io.o_slct_prev.get
      }
      m_llcross.get.io.b_m(0) <> m_l0dcross.io.b_s(1)
      m_llcross.get.io.b_m(1) <> m_l1d.get.io.b_next
      m_llcross.get.io.b_s(0) <> io.b_dmem.get
    }     
  }

  // ------------------------------
  //              L2
  // ------------------------------
  val w_l2_cbo = Wire(Vec(2, Bool())) 

  w_l2_cbo(0) := false.B
  w_l2_cbo(1) := true.B

  if (p.useL2) {
    if (p.useField) {
      m_l2.get.io.b_field.get <> m_hfu.get.io.b_field
      for (f <- 0 until p.nField) {
        if (p.useL1I && p.useL1D) {
          m_l2.get.io.b_field.get(f).flush := m_hfu.get.io.b_field(f).flush & m_l1i.get.io.b_field.get(f).free & m_l1d.get.io.b_field.get(f).free
        } else if (p.useL1I) {        
          m_l2.get.io.b_field.get(f).flush := m_hfu.get.io.b_field(f).flush & m_l1i.get.io.b_field.get(f).free
        } else if (p.useL1D) {        
          m_l2.get.io.b_field.get(f).flush := m_hfu.get.io.b_field(f).flush & m_l1d.get.io.b_field.get(f).free
        }
      }

      m_l2.get.io.b_part.get <> m_hfu.get.io.b_pall
      for (pa <- 0 until p.nPart) {
        if (p.useL1I && p.useL1D) {
          m_l2.get.io.b_part.get.state(pa).flush :=  m_hfu.get.io.b_pall.state(pa).flush & m_l1i.get.io.b_part.get.state(pa).free & m_l1d.get.io.b_part.get.state(pa).free
        } else if (p.useL1I) {        
          m_l2.get.io.b_part.get.state(pa).flush :=  m_hfu.get.io.b_pall.state(pa).flush & m_l1i.get.io.b_part.get.state(pa).free
        } else if (p.useL1D) {        
          m_l2.get.io.b_part.get.state(pa).flush :=  m_hfu.get.io.b_pall.state(pa).flush & m_l1d.get.io.b_part.get.state(pa).free
        }
      }
    }

    if (p.useCbo) {
      if (p.useL1D) {
        w_l2_cbo(0) := m_pipe.io.b_cbo.get.any & ~m_pipe.io.b_cbo.get.zero
      } else {
        w_l2_cbo(0) := m_pipe.io.b_cbo.get.any
      }      
      w_l2_cbo(1) := ~w_l2_cbo(0) | m_l2.get.io.b_cbo(0).ready
      m_l2.get.io.b_cbo(0) <> m_pipe.io.b_cbo.get
      m_l2.get.io.b_cbo(0).valid := m_pipe.io.b_cbo.get.valid & w_l2_cbo(0) & ((w_l1i_cbo(1) & w_l1d_cbo(1)) | m_pipe.io.b_cbo.get.hint)
    }

    if (p.useField) {
      if (p.useL1D) {
        m_l2.get.io.i_slct_prev.get := m_l1d.get.io.o_slct_next.get
      } else if (p.useL1I) {
        m_l2.get.io.i_slct_prev.get := m_l1i.get.io.o_slct_next.get
      } else {
        m_l2.get.io.i_slct_prev.get := m_pall.get.io.o_slct
      }
    }
   
    if (p.useL1I) {
      m_l2.get.io.b_prev(0) <> m_l1i.get.io.b_next
    } else {
      m_l2.get.io.b_prev(0) <> m_pipe.io.b_imem
    }
    if (p.useL1I) {
      m_l2.get.io.b_prev(1) <> m_l1d.get.io.b_next
    } else {
      m_l2.get.io.b_prev(1) <> m_l0dcross.io.b_s(2)
    }

    if (p.useField) {
      m_llcross.get.io.b_field.get <> m_hfu.get.io.b_field
      m_llcross.get.io.i_slct_req.get := m_l2.get.io.o_slct_next.get
      m_llcross.get.io.i_slct_read.get := m_l2.get.io.o_slct_prev.get
      m_llcross.get.io.i_slct_write.get := m_l2.get.io.o_slct_prev.get
    }
    m_llcross.get.io.b_m(0) <> m_l0dcross.io.b_s(1)
    m_llcross.get.io.b_m(1) <> m_l2.get.io.b_next
    m_llcross.get.io.b_s(0) <> io.b_mem.get
  }
  
  // ******************************
  //              CBO
  // ******************************
  if (p.useCbo) {
    m_pipe.io.b_cbo.get.ready := w_l1i_cbo(1) & w_l1d_cbo(1) & w_l2_cbo(1)
  } 

  // ******************************
  //              I/Os
  // ******************************
  // ------------------------------
  //             CLINT
  // ------------------------------
  if (p.useChamp) {
    m_io.io.b_field.get <> m_hfu.get.io.b_field

    m_io.io.i_irq_lei.get := io.i_irq_lei.get 
    m_io.io.i_irq_lsi.get := io.i_irq_lsi.get 
  } else {
    m_io.io.i_irq_mei.get := io.i_irq_mei.get
    m_io.io.i_irq_msi.get := io.i_irq_msi.get
  }

  // ------------------------------
  //              HPC
  // ------------------------------
  m_io.io.i_hpc_pipe(0) := m_pipe.io.o_hpc

  m_io.io.i_hpc_mem(0) := 0.U.asTypeOf(m_io.io.i_hpc_mem(0))
  if (p.useL1I) {
    m_io.io.i_hpc_mem(0).l1ihit := m_l1i.get.io.o_hpc(0).hit
    m_io.io.i_hpc_mem(0).l1ipftch := m_l1i.get.io.o_hpc(0).pftch
    m_io.io.i_hpc_mem(0).l1imiss := m_l1i.get.io.o_hpc(0).miss
  }
  if (p.useL1D) {
    m_io.io.i_hpc_mem(0).l1dhit := m_l1d.get.io.o_hpc(0).hit
    m_io.io.i_hpc_mem(0).l1dpftch := m_l1d.get.io.o_hpc(0).pftch
    m_io.io.i_hpc_mem(0).l1dmiss := m_l1d.get.io.o_hpc(0).miss
  }
  if (p.useL2) {
    m_io.io.i_hpc_mem(0).l2hit := m_l2.get.io.o_hpc(0).hit
    m_io.io.i_hpc_mem(0).l2pftch := m_l2.get.io.o_hpc(0).pftch
    m_io.io.i_hpc_mem(0).l2miss := m_l2.get.io.o_hpc(0).miss
  }

  // ******************************
  //              HFU
  // ******************************
  if (p.useChamp) {
    // ------------------------------
    //             PORT
    // ------------------------------
    m_hfu.get.io.b_port <> m_pipe.io.b_hfu.get    

    // ------------------------------
    //          FIELD STATE
    // ------------------------------
    val w_field_free = Wire(Vec(p.nField, Vec(7, Bool())))

    for (f <- 0 until p.nField) {
      for (l <- 0 until 7) {
        w_field_free(f)(l) := true.B
      }
    }

    io.b_field.get <> m_hfu.get.io.b_field

    for (f <- 0 until p.nField) {
      w_field_free(f)(0) := m_pipe.io.b_field.get(f).free
      w_field_free(f)(1) := m_l0dcross.io.b_field.get(f).free
      w_field_free(f)(2) := m_io.io.b_field.get(f).free
      if (p.useL1I) w_field_free(f)(3) := m_l1i.get.io.b_field.get(f).free
      if (p.useL1D) w_field_free(f)(4) := m_l1d.get.io.b_field.get(f).free
      if (p.useL2) w_field_free(f)(5) := m_l2.get.io.b_field.get(f).free
      w_field_free(f)(6) := io.b_field.get(f).free

      m_hfu.get.io.b_field(f).free := w_field_free(f).asUInt.andR
    }

    // ------------------------------
    //           HART STATE
    // ------------------------------

    // ------------------------------
    //   EXECUTED FIELD PART STATE
    // ------------------------------
    for (pa <- 0 until p.nPart) {
      m_hfu.get.io.b_pexe.state(pa).free := m_pipe.io.b_part.get.state(pa).free
    }

    // ------------------------------
    //        ALL PART STATE
    // ------------------------------
    val w_pall_free = Wire(Vec(p.nPart, Vec(4, Bool())))

    for (pa <- 0 until p.nPart) {
      for (l <- 0 until 4) {
        w_pall_free(pa)(l) := true.B
      }
    }

    for (pa <- 0 until p.nPart) {
      io.b_pall.get.state(pa) <> m_hfu.get.io.b_pall.state(pa)
    }
    io.b_pall.get.weight := DontCare

    for (pa <- 0 until p.nPart) {
      if (p.useL1I) w_pall_free(pa)(0) := m_l1i.get.io.b_part.get.state(pa).free
      if (p.useL1D) w_pall_free(pa)(1) := m_l1d.get.io.b_part.get.state(pa).free
      if (p.useL2) w_pall_free(pa)(2) := m_l2.get.io.b_part.get.state(pa).free
      w_pall_free(pa)(3) := io.b_pall.get.state(pa).free

      m_hfu.get.io.b_pall.state(pa).free := w_pall_free(pa).asUInt.andR
    }
  }

  // ******************************
  //             DEBUG
  // ******************************
  if (p.debug) {
    // ------------------------------
    //            SIGNALS
    // ------------------------------
    io.o_dbg.get.last := m_pipe.io.o_dbg.get.last
    io.o_dbg.get.x := m_pipe.io.o_dbg.get.x
    io.o_dbg.get.csr := m_pipe.io.o_dbg.get.csr
    if (p.useChamp) io.o_dbg.get.hf.get := m_hfu.get.io.o_dbg.get
    io.o_dbg.get.hpc := m_io.io.o_dbg.get.hpc(0)

    // ------------------------------
    //       EXECUTION TRACKER
    // ------------------------------
    io.o_etd.get := m_pipe.io.o_etd.get
  } 
}

object Salers extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new Salers(SalersConfigBase), args)
}
