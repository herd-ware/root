/*
 * File: pipeline.scala                                                        *
 * Created Date: 2023-03-08 01:51:25 pm                                        *
 * Author: Mathieu Escouteloup                                                 *
 * -----                                                                       *
 * Last Modified: 2023-04-12 09:56:50 am                                       *
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

import herd.common.field._
import herd.common.core.{HpcPipelineBus}
import herd.common.mem.mb4s._
import herd.common.mem.cbo._
import herd.core.aubrac.nlp._
import herd.core.aubrac.front._
import herd.core.aubrac.back.csr.{CsrMemIO}
import herd.core.aubrac.hfu._
import herd.core.aubrac.common._
import herd.core.salers.back._
import herd.io.core.clint.{ClintIO}


class Pipeline (p: PipelineParams) extends Module {
  val io = IO(new Bundle {
    val b_field = if (p.useField) Some(Vec(p.nField, new FieldIO(p.nAddrBit, p.nDataBit))) else None
    val b_hart = if (p.useField) Some(new RsrcIO(p.nHart, p.nField, p.nHart)) else None
    val b_part = if (p.useField) Some(new NRsrcIO(p.nHart, p.nField, p.nPart)) else None

    val b_imem = new Mb4sIO(p.pL0IBus)

    val b_dmem = new Mb4sIO(p.pL0DBus)
    val b_cbo = if (p.useCbo) Some(new CboIO(p.nHart, p.useField, p.nField, p.nAddrBit)) else None
    val b_hfu = if (p.useChamp) Some(Flipped(new HfuIO(p, p.nAddrBit, p.nDataBit, p.nChampTrapLvl))) else None
    val b_clint = Flipped(new ClintIO(p.nDataBit))

    val o_hpc = Output(new HpcPipelineBus())
    val i_hpm = Input(Vec(32, UInt(64.W)))

    val o_dbg = if (p.debug) Some(Output(new PipelineDbgBus(p))) else None
    val o_etd = if (p.debug) Some(Output(Vec(p.nCommit, new EtdBus(p.nHart, p.nAddrBit, p.nInstrBit)))) else None
  })

  val m_nlp = if (p.useNlp) Some(Module(new Nlp(p))) else None
  val m_front = Module(new Front(p))
  val m_back = Module(new Back(p))

  // ******************************
  //          FRONT & NLP
  // ******************************
  if (p.useNlp) {    
    if (p.useField) m_nlp.get.io.b_hart.get <> io.b_hart.get
    m_nlp.get.io.i_mispred := m_back.io.o_br_new(0).valid
    m_nlp.get.io.b_read <> m_front.io.b_nlp.get
    m_nlp.get.io.i_info := m_back.io.o_br_info(0)
  }
  
  if (p.useField) {
    m_front.io.b_hart.get <> io.b_hart.get
    m_front.io.i_flush := m_back.io.o_flush(0) | io.b_hfu.get.ctrl.pipe_flush
    m_front.io.i_br_field.get := io.b_hfu.get.ctrl.pipe_br
  } else {
    m_front.io.i_flush := m_back.io.o_flush(0)
  }
  m_front.io.i_br_new := m_back.io.o_br_new(0)
  m_front.io.b_imem <> io.b_imem
  for (bp <- 0 until p.nBackPort) {
    m_front.io.b_out(bp).ready := m_back.io.b_in(bp).ready
  }

  // ******************************
  //             BACK
  // ******************************  
  if (p.useField) {
    m_back.io.b_field.get <> io.b_field.get
    m_back.io.b_hart.get(0) <> io.b_hart.get
    m_back.io.i_flush(0) := io.b_hfu.get.ctrl.pipe_flush
  } else {
    m_back.io.i_flush(0) := false.B
  }
  m_back.io.i_br_next(0) := m_front.io.o_br_next

  for (bp <- 0 until p.nBackPort) {
    m_back.io.b_in(bp).valid := m_front.io.b_out(bp).valid
    m_back.io.b_in(bp).ctrl.get.hart := 0.U
    m_back.io.b_in(bp).ctrl.get.pc := m_front.io.b_out(bp).ctrl.get.pc
    m_back.io.b_in(bp).ctrl.get.instr := m_front.io.b_out(bp).ctrl.get.instr
  }

  m_back.io.b_d0mem <> io.b_dmem   
  if (p.useCbo) m_back.io.b_cbo.get(0) <> io.b_cbo.get
  if (p.useChamp) m_back.io.b_hfu.get(0) <> io.b_hfu.get

  io.o_hpc := m_back.io.o_hpc(0)
  m_back.io.i_hpm(0) := io.i_hpm
  m_back.io.b_clint(0) <> io.b_clint

  // ******************************
  //             FIELD
  // ******************************
  if (p.useField) {
    io.b_field.get <> m_back.io.b_field.get
    if (p.useNlp) {
      io.b_hart.get.free := m_front.io.b_hart.get.free & m_back.io.b_hart.get(0).free & m_nlp.get.io.b_hart.get.free
    } else {
      io.b_hart.get.free := m_front.io.b_hart.get.free & m_back.io.b_hart.get(0).free
    }   
    io.b_part.get <> m_back.io.b_part.get  
  }

  // ******************************
  //             DEBUG
  // ******************************
  if (p.debug) {
    // ------------------------------
    //            SIGNALS
    // ------------------------------
    io.o_dbg.get.last := m_back.io.o_dbg.get.last(0)
    io.o_dbg.get.x := m_back.io.o_dbg.get.x(0)
    io.o_dbg.get.csr := m_back.io.o_dbg.get.csr(0)

    // ------------------------------
    //       EXECUTION TRACKER
    // ------------------------------
    for (bp <- 0 until p.nBackPort) {
      m_back.io.b_in(bp).ctrl.get.etd.get := m_front.io.b_out(bp).ctrl.get.etd.get
    }

    io.o_etd.get := m_back.io.o_etd.get
  } 
}

object Pipeline extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new Pipeline(PipelineConfigBase), args)
}
