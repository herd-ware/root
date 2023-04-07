/*
 * File: bus.scala                                                             *
 * Created Date: 2023-03-08 01:51:25 pm                                        *
 * Author: Mathieu Escouteloup                                                 *
 * -----                                                                       *
 * Last Modified: 2023-03-09 12:13:16 pm                                       *
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
import scala.math._

import herd.common.core.{HpcInstrBus}
import herd.common.mem.mb4s.{OP => LSUUOP, AMO => LSUAMO}
import herd.core.aubrac.common._
import herd.core.aubrac.back.{DataBus, InfoBus, IntCtrlBus, LsuCtrlBus, CsrCtrlBus, GprCtrlBus, ExtCtrlBus}
import herd.core.aubrac.back.{INTUNIT, LSUSIZE, LSUSIGN}
import herd.core.aubrac.back.csr.{UOP => CSRUOP, CsrBus}
import herd.core.aubrac.hfu.{CODE => DMUCODE, OP => DMUOP}


// ******************************
//          DISPATCHER
// ******************************
class DispatcherIO(p: DispatcherParams) extends Bundle {
  val valid = Output(Bool())
  val hart = Output(UInt(log2Ceil(p.nHart).W))
  val unit = Output(UInt(INTUNIT.NBIT.W))
  val ready = Input(Bool())
  val port = Input(UInt(log2Ceil(p.nBackPort).W))
}

class FreeBus(p: DispatcherParams) extends Bundle {
  val alu = Input(Vec(p.nAlu, Bool()))
  val bru = Input(Vec(p.nBru, Bool()))
  val muldiv = if (p.nMulDiv > 0) Some(Input(Vec(p.nMulDiv, Bool()))) else None
}

// ******************************
//          CONTROL BUS
// ******************************
class DataStaticBus(nDataBit: Int) extends DataBus(nDataBit) {
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val s1_is_reg = Bool()
  val s2_is_reg = Bool()
  val s3_is_reg = Bool()
}

class LsuStateCtrlBus extends Bundle {
  val use = Bool()
  val load = Bool()
}

// ******************************
//       STAGE CONTROL BUS
// ******************************
class IssCtrlBus(p: BackParams) extends Bundle {
  val info = new InfoBus(p.nHart, p.nAddrBit, p.nInstrBit)
  val trap = new TrapBus(p.nAddrBit, p.nDataBit)

  val int = new IntCtrlBus(p.nBackPort)
  val lsu = new LsuCtrlBus()
  val csr = new CsrCtrlBus()
  val gpr = new GprCtrlBus()

  val ext = new ExtCtrlBus()

  val hpc = new HpcInstrBus()
  val data = new DataStaticBus(p.nDataBit)

  val etd = if (p.debug) Some(new EtdBus(p.nHart, p.nAddrBit, p.nInstrBit)) else None
}

class Ex0CtrlBus(p: BackParams) extends Bundle {
  val info = new InfoBus(p.nHart, p.nAddrBit, p.nInstrBit)
  val trap = new TrapBus(p.nAddrBit, p.nDataBit)

  val int = new IntCtrlBus(p.nBackPort)
  val lsu = new LsuCtrlBus()
  val csr = new CsrCtrlBus()
  val gpr = new GprCtrlBus()

  val ext = new ExtCtrlBus()

  val hpc = new HpcInstrBus()
  
  val etd = if (p.debug) Some(new EtdBus(p.nHart, p.nAddrBit, p.nInstrBit)) else None
}

class Ex1CtrlBus(p: BackParams) extends Bundle {
  val info = new InfoBus(p.nHart, p.nAddrBit, p.nInstrBit)
  val trap = new TrapBus(p.nAddrBit, p.nDataBit)

  val int = new IntCtrlBus(p.nBackPort)
  val lsu = new LsuStateCtrlBus()
  val csr = new CsrCtrlBus()
  val gpr = new GprCtrlBus()

  val ext = new ExtCtrlBus()
  
  val hpc = new HpcInstrBus()
  
  val etd = if (p.debug) Some(new EtdBus(p.nHart, p.nAddrBit, p.nInstrBit)) else None
}

class MemCtrlBus(p: BackParams) extends Bundle {
  val info = new InfoBus(p.nHart, p.nAddrBit, p.nInstrBit)
  val trap = new TrapBus(p.nAddrBit, p.nDataBit)

  val lsu = new LsuStateCtrlBus()
  val csr = new CsrCtrlBus()
  val gpr = new GprCtrlBus()

  val ext = new ExtCtrlBus()
  
  val hpc = new HpcInstrBus()
  
  val etd = if (p.debug) Some(new EtdBus(p.nHart, p.nAddrBit, p.nInstrBit)) else None
}

class WbCtrlBus(p: BackParams) extends Bundle {
  val info = new InfoBus(p.nHart, p.nAddrBit, p.nInstrBit)
  val trap = new TrapBus(p.nAddrBit, p.nDataBit)

  val lsu = new LsuStateCtrlBus()
  val csr = new CsrCtrlBus()
  val gpr = new GprCtrlBus()

  val ext = new ExtCtrlBus()
  
  val hpc = new HpcInstrBus()
  
  val etd = if (p.debug) Some(new EtdBus(p.nHart, p.nAddrBit, p.nInstrBit)) else None
}

// ******************************
//        LOAD-STORE UNIT
// ******************************
class LsuReqCtrlBus(p: BackParams) extends Bundle {
  val hart = UInt(log2Ceil(p.nHart).W)
  val uop = UInt(LSUUOP.NBIT.W)
  val amo = UInt(LSUAMO.NBIT.W)
  val size = UInt(LSUSIZE.NBIT.W)
  val sign = UInt(LSUSIGN.NBIT.W)
  val addr = UInt(p.nAddrBit.W)
  val trap = new TrapBus(p.nAddrBit, p.nDataBit)

  def ld: Bool = (uop === LSUUOP.R) | (uop === LSUUOP.LR) | (uop === LSUUOP.SC) | (uop === LSUUOP.AMO)
  def st: Bool = (uop === LSUUOP.W) | (uop === LSUUOP.SC) | (uop === LSUUOP.AMO)
  def sc: Bool = (uop === LSUUOP.SC)
  def a: Bool = (uop === LSUUOP.AMO) | (uop === LSUUOP.SC)
}

class LsuMemCtrlBus(p: BackParams) extends Bundle {
  val hart = UInt(log2Ceil(p.nHart).W)
  val uop = UInt(LSUUOP.NBIT.W)
  val amo = UInt(LSUAMO.NBIT.W)
  val port = UInt(1.W)
  val size = UInt(LSUSIZE.NBIT.W)
  val sign = UInt(LSUSIGN.NBIT.W)
  val trap = new TrapBus(p.nAddrBit, p.nDataBit)

  def ld: Bool = (uop === LSUUOP.R) | (uop === LSUUOP.LR) | (uop === LSUUOP.SC) | (uop === LSUUOP.AMO)
  def st: Bool = (uop === LSUUOP.W) | (uop === LSUUOP.SC) | (uop === LSUUOP.AMO)
  def sc: Bool = (uop === LSUUOP.SC)
  def a: Bool = (uop === LSUUOP.AMO) | (uop === LSUUOP.SC)
}

class LsuAckCtrlBus(p: BackParams) extends Bundle {
  val trap = new TrapBus(p.nAddrBit, p.nDataBit)
}

// ******************************
//             DEBUG
// ******************************
class BackDbgBus (p: BackParams) extends Bundle {
  val last = Vec(p.nHart, UInt(p.nAddrBit.W))
  val x = Vec(p.nHart, Vec(32, UInt(p.nDataBit.W)))
  val csr = Vec(p.nHart, new CsrBus(p.nDataBit, p.useChamp))
}
