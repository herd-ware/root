/*
 * File: ex.scala                                                              *
 * Created Date: 2023-03-08 01:51:25 pm                                        *
 * Author: Mathieu Escouteloup                                                 *
 * -----                                                                       *
 * Last Modified: 2023-04-12 09:10:24 am                                       *
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
import herd.common.mem.cbo._
import herd.common.mem.mb4s.{OP => LSUUOP}
import herd.core.aubrac.common._
import herd.core.aubrac.back.{DataBus,BypassBus,ResultBus,StageBus,IntUnitCtrlBus,IntUnitDataBus}
import herd.core.aubrac.back.{Alu,Bru,MulDiv}
import herd.core.aubrac.back.{INTUNIT,LSUSIGN,LSUSIZE,EXT}
import herd.core.aubrac.back.csr.{CsrReadIO}
import herd.core.aubrac.nlp.{BranchInfoBus}
import herd.core.aubrac.hfu.{HfuReqCtrlBus,HfuReqDataBus}


class ExStage(p: BackParams) extends Module {
  require ((p.nExStage >= 1) && (p.nExStage <= 3), "Only 1 to 3 EX stages are possible.")
  
  val io = IO(new Bundle {
    val b_hart = if (p.useField) Some(Vec(p.nHart, new RsrcIO(p.nHart, p.nField, p.nHart))) else None
    val b_back = if (p.useField) Some(Vec(p.nBackPort, new RsrcIO(p.nHart, p.nField, p.nBackPort))) else None
    val b_alu = if (p.useField) Some(Vec(p.nAlu, new RsrcIO(p.nHart, p.nField, p.nAlu))) else None
    val b_muldiv = if (p.useField && (p.nMulDiv > 0)) Some(Vec(p.nMulDiv, new RsrcIO(p.nHart, p.nField, p.nMulDiv))) else None

    val i_flush = Input(Vec(p.nHart, Bool()))
    val o_flush = Output(Vec(p.nHart, Bool()))

    val b_in = Vec(p.nBackPort, Flipped(new GenRVIO(p, new Ex0CtrlBus(p), new DataBus(p.nDataBit))))

    val o_stage = Output(Vec(p.nExStage, Vec(p.nBackPort, new StageBus(p.nHart, p.nAddrBit, p.nInstrBit))))
    val o_free = Output(new FreeBus(p))
    val i_exc_on = Input(Vec(p.nHart, Bool()))
    val i_br_next = Input(Vec(p.nHart, new BranchBus(p.nAddrBit)))

    val b_lsu = Vec(p.nBackPort, new GenRVIO(p, new LsuReqCtrlBus(p), UInt(p.nDataBit.W)))   
    val b_cbo = if (p.useCbo) Some(Vec(p.nHart, new CboIO(p.nHart, p.useField, p.nField, p.nAddrBit))) else None
    val b_hfu = if (p.useChamp) Some(Vec(p.nHart, new GenRVIO(p, new HfuReqCtrlBus(p.debug, p.nAddrBit), new HfuReqDataBus(p.nDataBit)))) else None

    val o_byp = Output(Vec((p.nExStage * p.nBackPort), new BypassBus(p.nHart, p.nDataBit)))
    val o_br_new = Output(Vec(p.nHart, new BranchBus(p.nAddrBit)))
    val o_br_info = Output(Vec(p.nHart, new BranchInfoBus(p.nAddrBit)))

    val b_out = Vec(p.nBackPort, new GenRVIO(p, new MemCtrlBus(p), new ResultBus(p.nDataBit)))
  })

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
  //  STAGE REGISTERS AND SIGNALS
  // ******************************
  // ------------------------------
  //              EX0
  // ------------------------------
  val w_ex0 = Wire(Vec(p.nBackPort, new GenVBus(p, new IntUnitCtrlBus(p.nHart, p.useField, p.nField, p.nAddrBit), new IntUnitDataBus(p.nDataBit))))
  
  val w_ex0_br_next = Wire(Vec(p.nBackPort, new BranchBus(p.nAddrBit)))
  val w_ex0_flush = Wire(Vec(p.nBackPort, Bool()))
  val w_ex0_wait = Wire(Vec(p.nBackPort, Bool()))
  val w_ex0_wait_bp = Wire(Vec(p.nBackPort, Bool()))
  val w_ex0_wait_unit = Wire(Vec(p.nBackPort, Bool()))
  val w_ex0_wait_lsu = Wire(Vec(p.nBackPort, Bool()))
  val w_ex0_wait_st = Wire(Vec(p.nBackPort, Bool()))
  val w_ex0_pred_exc = Wire(Vec(p.nBackPort, Bool()))
  val w_ex0_hfu_av = Wire(Vec(p.nBackPort, Bool()))
  val w_ex0_lock = Wire(Vec(p.nBackPort, Bool()))
  
  val m_ex0 = Seq.fill(p.nBackPort){Module(new GenReg(p, new Ex1CtrlBus(p), new ResultBus(p.nDataBit), false, false, true))}

  // ------------------------------
  //              EX1
  // ------------------------------
  val w_ex1 = Wire(Vec(p.nBackPort, new GenVBus(p, new Ex1CtrlBus(p), new ResultBus(p.nDataBit))))

  val w_ex1_flush = Wire(Vec(p.nBackPort, Bool()))
  val w_ex1_wait = Wire(Vec(p.nBackPort, Bool()))
  val w_ex1_wait_bp = Wire(Vec(p.nBackPort, Bool()))
  val w_ex1_wait_unit = Wire(Vec(p.nBackPort, Bool()))
  val w_ex1_lock = Wire(Vec(p.nBackPort, Bool()))
  val w_ex1_br_new = Wire(Vec(p.nBackPort, new BranchBus(p.nAddrBit)))
  val w_ex1_exc_on = Wire(Vec(p.nHart, Vec(p.nBackPort, Bool())))

  // Flush register with branches
  val r_flush_pipe = RegInit(VecInit(Seq.fill(p.nBackPort)(false.B)))
  
  val w_flush_pipe = Wire(Vec(p.nBackPort, Bool()))

  val m_ex1 = Seq.fill(p.nBackPort){Module(new GenReg(p, new Ex1CtrlBus(p), new ResultBus(p.nDataBit), false, false, true))}
  
  // ------------------------------
  //              EX2
  // ------------------------------
  val w_ex2 = Wire(Vec(p.nBackPort, new GenVBus(p, new Ex1CtrlBus(p), new ResultBus(p.nDataBit))))

  val w_ex2_flush = Wire(Vec(p.nBackPort, Bool()))
  val w_ex2_wait = Wire(Vec(p.nBackPort, Bool()))
  val w_ex2_wait_bp = Wire(Vec(p.nBackPort, Bool()))
  val w_ex2_wait_unit = Wire(Vec(p.nBackPort, Bool()))
  val w_ex2_lock = Wire(Vec(p.nBackPort, Bool()))
  val w_ex2_exc_on = Wire(Vec(p.nHart, Vec(p.nBackPort, Bool())))

  val m_out = Seq.fill(p.nBackPort){Module(new GenReg(p, new MemCtrlBus(p), new ResultBus(p.nDataBit), false, false, true))}

  // ******************************
  //              EX0
  // ******************************
  // ------------------------------
  //      CURRENT NEXT BRANCH
  // ------------------------------
  for (bp0 <- 0 until p.nBackPort) {
    w_ex0_br_next(bp0) := io.i_br_next(io.b_in(bp0).ctrl.get.info.hart)

    for (bp1 <- (p.nBackPort - 1) to (bp0 + 1) by -1) {
      when (io.b_in(bp1).valid & (io.b_in(bp1).ctrl.get.info.hart === io.b_in(bp0).ctrl.get.info.hart)) {
        w_ex0_br_next(bp0).valid := true.B
        w_ex0_br_next(bp0).addr := io.b_in(bp1).ctrl.get.info.pc
      }
    }
  }

  // ------------------------------
  //         UNITS: DEFAULT
  // ------------------------------
  for (bp <- 0 until p.nBackPort) {
    w_ex0_wait_unit(bp) := false.B

    w_ex0(bp).valid := io.b_in(bp).valid & ~w_ex0_flush(bp)
    w_ex0(bp).ctrl.get.hart := io.b_in(bp).ctrl.get.info.hart
    if (p.useField) w_ex0(bp).ctrl.get.field.get := io.b_back.get(bp).field
    w_ex0(bp).ctrl.get.uop := io.b_in(bp).ctrl.get.int.uop
    w_ex0(bp).ctrl.get.pc := io.b_in(bp).ctrl.get.info.pc
    w_ex0(bp).ctrl.get.ssign := io.b_in(bp).ctrl.get.int.ssign
    w_ex0(bp).ctrl.get.ssize := io.b_in(bp).ctrl.get.int.ssize
    w_ex0(bp).ctrl.get.rsize := io.b_in(bp).ctrl.get.int.rsize
    w_ex0(bp).data.get.s1 := io.b_in(bp).data.get.s1
    w_ex0(bp).data.get.s2 := io.b_in(bp).data.get.s2
    w_ex0(bp).data.get.s3 := io.b_in(bp).data.get.s3
  }

  // ------------------------------
  //          UNITS: ALU
  // ------------------------------
  val m_alu = for (a <- 0 until p.nAlu) yield {
    if (a < p.isBAlu.size) {
      val m_alu = Module(new Alu(p, p.nDataBit, (p.nExStage > 1), p.isBAlu(a)))
      m_alu
    } else {
      val m_alu = Module(new Alu(p, p.nDataBit, (p.nExStage > 1), false))
      m_alu
    }
  } 

  for (a <- 0 until p.nAlu) {
    io.o_free.alu(a) := true.B
    if (p.nBAlu > 0) io.o_free.balu.get(a) := false.B

    if (p.useField) {
      m_alu(a).io.i_flush := io.b_alu.get(a).flush
    } else {
      m_alu(a).io.i_flush := false.B
    }
    m_alu(a).io.b_port.req := DontCare
    m_alu(a).io.b_port.req.valid := false.B

    if (p.nAlu == p.nBackPort) {
      when (w_ex0(a).valid & (io.b_in(a).ctrl.get.int.unit === INTUNIT.ALU)) {
        if (p.nExStage > 1) {
          io.o_free.alu(a) := ~(w_ex0_wait_bp(a) | w_ex0_wait_lsu(a) | w_ex0_lock(a))
          if ((p.nBAlu > 0) && (a < p.isBAlu.size)) {
            if (p.isBAlu(a)) {
              io.o_free.balu.get(a) := ~(w_ex0_wait_bp(a) | w_ex0_wait_lsu(a) | w_ex0_lock(a))
            }
          }

          w_ex0_wait_unit(a) := ~m_alu(a).io.b_port.req.ready
          m_alu(a).io.b_port.req.valid := ~(w_ex0_wait_bp(a) | w_ex0_wait_lsu(a) | w_ex0_lock(a))
        } else {
          io.o_free.alu(a) := ~(w_ex0_wait_bp(a) | w_ex0_wait_lsu(a) | w_ex0_lock(a) | w_ex1_wait(a) | w_ex2_wait(a))
          if ((p.nBAlu > 0) && (a < p.isBAlu.size)) {
            if (p.isBAlu(a)) {
              io.o_free.balu.get(a) := ~(w_ex0_wait_bp(a) | w_ex0_wait_lsu(a) | w_ex0_lock(a) | w_ex1_wait(a) | w_ex2_wait(a))
            }
          }

          w_ex0_wait_unit(a) := false.B
          m_alu(a).io.b_port.req.valid := true.B
        }
      }

      m_alu(a).io.b_port.req.ctrl.get := w_ex0(a).ctrl.get
      m_alu(a).io.b_port.req.data.get := w_ex0(a).data.get
    } else {
      for (bp <- 0 until p.nBackPort) {
        when (w_ex0(bp).valid & (io.b_in(bp).ctrl.get.int.unit === INTUNIT.ALU) & (io.b_in(bp).ctrl.get.int.port === a.U)) {
          if (p.nExStage > 1) {
            io.o_free.alu(a) := ~(w_ex0_wait_bp(bp) | w_ex0_wait_lsu(bp) | w_ex0_lock(bp))
            if ((p.nBAlu > 0) && (a < p.isBAlu.size)) {
              if (p.isBAlu(a)) {
                io.o_free.balu.get(a) := ~(w_ex0_wait_bp(bp) | w_ex0_wait_lsu(bp) | w_ex0_lock(bp))
              }
            }

            w_ex0_wait_unit(bp) := ~m_alu(a).io.b_port.req.ready
            m_alu(a).io.b_port.req.valid := ~(w_ex0_wait_bp(bp) | w_ex0_wait_lsu(bp) | w_ex0_lock(bp))
          } else {
            io.o_free.alu(a) := ~(w_ex0_wait_bp(bp) | w_ex0_wait_lsu(bp) | w_ex0_lock(bp) | w_ex1_wait(bp) | w_ex2_wait(bp))
            if ((p.nBAlu > 0) && (a < p.isBAlu.size)) {
              if (p.isBAlu(a)) {
                io.o_free.balu.get(a) := ~(w_ex0_wait_bp(bp) | w_ex0_wait_lsu(bp) | w_ex0_lock(bp) | w_ex1_wait(bp) | w_ex2_wait(bp))
              }
            }

            w_ex0_wait_unit(bp) := false.B
            m_alu(a).io.b_port.req.valid := true.B
          }

          m_alu(a).io.b_port.req.ctrl.get := w_ex0(bp).ctrl.get
          m_alu(a).io.b_port.req.data.get := w_ex0(bp).data.get 
        }
      }
    }
  }

  // ------------------------------
  //          UNITS: BRU
  // ------------------------------
  val m_bru = Seq.fill(p.nBru) {Module(new Bru(p, p.nHart, p.nAddrBit, p.nDataBit,p.useExtZifencei, p.useExtZicbo, (p.nExStage > 1)))}

  for (b <- 0 until p.nBru) {
    io.o_free.bru(b) := true.B

    if (p.useField) {
      m_bru(b).io.i_flush := io.b_hart.get(b).flush
    } else {
      m_bru(b).io.i_flush := false.B
    }
    m_bru(b).io.b_port.req := DontCare
    m_bru(b).io.b_port.req.valid := false.B
    m_bru(b).io.i_br_next := DontCare
    m_bru(b).io.i_call := DontCare
    m_bru(b).io.i_ret := DontCare

    if (p.useCbo) m_bru(b).io.b_cbo.get <> io.b_cbo.get(b)

    for (bp <- 0 until p.nBackPort) {
      when (w_ex0(bp).valid & (io.b_in(bp).ctrl.get.int.unit === INTUNIT.BRU) & (io.b_in(bp).ctrl.get.info.hart === b.U)) {
        if (p.nExStage > 1) {
          io.o_free.bru(b) := ~(w_ex0_wait_bp(bp) | w_ex0_lock(bp))
          w_ex0_wait_unit(bp) := ~m_bru(b).io.b_port.req.ready 
          m_bru(b).io.b_port.req.valid := ~(w_ex0_wait_bp(bp) | w_ex0_lock(bp))
        } else {
          io.o_free.bru(b) := ~(w_ex0_wait_bp(bp) | w_ex0_lock(bp) | w_ex1_wait(bp) | w_ex2_wait(bp))
          w_ex0_wait_unit(bp) := false.B
          m_bru(b).io.b_port.req.valid := true.B
        }

        m_bru(b).io.b_port.req.ctrl.get := w_ex0(bp).ctrl.get
        m_bru(b).io.b_port.req.data.get := w_ex0(bp).data.get
        m_bru(b).io.i_br_next := w_ex0_br_next(bp)
        m_bru(b).io.i_call := io.b_in(bp).ctrl.get.int.call
        m_bru(b).io.i_ret := io.b_in(bp).ctrl.get.int.ret        
      }
    }
  }

  // ------------------------------
  //         UNITS: MULDIV
  // ------------------------------
  val m_muldiv = if (p.useExtM) Some(for (m <- 0 until p.nMulDiv) yield {
    if (m < p.isClMul.size) {
      val m_muldiv = Module(new MulDiv(p, p.nDataBit, (p.nExStage > 1), p.isClMul(m), 2))
      m_muldiv
    } else {
      val m_muldiv = Module(new MulDiv(p, p.nDataBit, (p.nExStage > 1), false, 2))
      m_muldiv
    }
  }) else None 

  if (p.useExtM) {
    for (m <- 0 until p.nMulDiv) {
      io.o_free.muldiv.get(m) := m_muldiv.get(m).io.o_free
      if (p.nClMul > 0) io.o_free.muldiv.get(m) := false.B

      if (p.useField) {
        m_muldiv.get(m).io.i_flush := io.b_muldiv.get(m).flush
      } else {
        m_muldiv.get(m).io.i_flush := false.B
      }
      m_muldiv.get(m).io.i_flush := false.B
      m_muldiv.get(m).io.b_port.req := DontCare
      m_muldiv.get(m).io.b_port.req.valid := false.B

      if (p.nMulDiv == p.nBackPort) {
        when (w_ex0(m).valid & (io.b_in(m).ctrl.get.int.unit === INTUNIT.MULDIV)) {
          if (p.nExStage > 1) {
            io.o_free.muldiv.get(m) := m_muldiv.get(m).io.o_free & ~(w_ex0_wait_bp(m) | w_ex0_lock(m))
            if ((p.nClMul > 0) && (m < p.isClMul.size)) {
              if (p.isClMul(m)) {
                io.o_free.clmul.get(m) := m_muldiv.get(m).io.o_free & ~(w_ex0_wait_bp(m) | w_ex0_lock(m))
              }
            }

            w_ex0_wait_unit(m) := ~m_muldiv.get(m).io.b_port.req.ready
            m_muldiv.get(m).io.b_port.req.valid := ~(w_ex0_wait_bp(m) | w_ex0_lock(m))
          } else {
            io.o_free.muldiv.get(m) := m_muldiv.get(m).io.o_free & ~(w_ex0_wait_bp(m) | w_ex0_lock(m) | w_ex1_wait(m) | w_ex2_wait(m))
            if ((p.nClMul > 0) && (m < p.isClMul.size)) {
              if (p.isClMul(m)) {
                io.o_free.clmul.get(m) := m_muldiv.get(m).io.o_free & ~(w_ex0_wait_bp(m) | w_ex0_lock(m) | w_ex1_wait(m) | w_ex2_wait(m))
              }
            }

            w_ex0_wait_unit(m) := false.B
            m_muldiv.get(m).io.b_port.req.valid := true.B
          }
        }

        m_muldiv.get(m).io.b_port.req.ctrl.get := w_ex0(m).ctrl.get
        m_muldiv.get(m).io.b_port.req.data.get := w_ex0(m).data.get
      } else {
        for (bp <- 0 until p.nBackPort) {
          when (w_ex0(bp).valid & (io.b_in(bp).ctrl.get.int.unit === INTUNIT.MULDIV) & (io.b_in(bp).ctrl.get.int.port === m.U)) {             
            if (p.nExStage > 1) {    
              io.o_free.muldiv.get(m) := m_muldiv.get(m).io.o_free & ~(w_ex0_wait_bp(bp) | w_ex0_lock(bp))
              if ((p.nClMul > 0) && (m < p.isClMul.size)) {
                if (p.isClMul(m)) {
                  io.o_free.clmul.get(m) := m_muldiv.get(m).io.o_free & ~(w_ex0_wait_bp(bp) | w_ex0_lock(bp))
                }
              }

              w_ex0_wait_unit(bp) := ~m_muldiv.get(m).io.b_port.req.ready 
              m_muldiv.get(m).io.b_port.req.valid := ~(w_ex0_wait_bp(bp) | w_ex0_lock(bp))
            } else {    
              io.o_free.muldiv.get(m) := m_muldiv.get(m).io.o_free & ~(w_ex0_wait_bp(bp) | w_ex0_lock(bp) | w_ex1_wait(bp) | w_ex2_wait(bp))
              if ((p.nClMul > 0) && (m < p.isClMul.size)) {
                if (p.isClMul(m)) {
                  io.o_free.clmul.get(m) := m_muldiv.get(m).io.o_free & ~(w_ex0_wait_bp(bp) | w_ex0_lock(bp) | w_ex1_wait(bp) | w_ex2_wait(bp))
                }
              }

              w_ex0_wait_unit(bp) := false.B
              m_muldiv.get(m).io.b_port.req.valid := true.B
            }

            m_muldiv.get(m).io.b_port.req.ctrl.get := w_ex0(bp).ctrl.get
            m_muldiv.get(m).io.b_port.req.data.get := w_ex0(bp).data.get          
          }
        }
      }
    }
  }

  // ------------------------------
  //          UNITS: HFU
  // ------------------------------
  if (p.useChamp) {
    for (bp <- 0 until p.nBackPort) {
      w_ex0_hfu_av(bp) := true.B

      when (io.b_in(bp).ctrl.get.ext.ext === EXT.HFU) {
        w_ex0_wait_unit(bp) := w_ex0_hfu_av(bp) & io.b_hfu.get(w_ex0(bp).ctrl.get.hart).ready
      }
    }

    for (bp0 <- 0 until p.nBackPort) {
      when (w_ex0(bp0).valid & (io.b_in(bp0).ctrl.get.ext.ext === EXT.HFU)) {
        for (bp1 <- (bp0 + 1) until p.nBackPort) {
          when (w_ex0(bp0).ctrl.get.hart === w_ex0(bp1).ctrl.get.hart) {
            w_ex0_hfu_av(bp1) := true.B
          }
        }
      }      
    }

    for (h <- 0 until p.nHart) {
      io.b_hfu.get(h) := DontCare
      io.b_hfu.get(h).valid := false.B

      for (bp <- 0 until p.nBackPort) {
        when (w_ex0(bp).valid & w_ex0_hfu_av(bp) & (io.b_in(bp).ctrl.get.ext.ext === EXT.HFU) & (h.U === w_ex0(bp).ctrl.get.hart)) {
          if (p.nExStage > 1) {
            io.b_hfu.get(h).valid := ~(w_ex0_wait_bp(bp) | w_ex0_lock(bp))
          } else {
            io.b_hfu.get(h).valid := ~(w_ex0_wait_bp(bp) | w_ex0_lock(bp) | w_ex1_wait(bp) | w_ex2_wait(bp))
          }
          io.b_hfu.get(h).ctrl.get.code := io.b_in(bp).ctrl.get.ext.code
          io.b_hfu.get(h).ctrl.get.op1 := io.b_in(bp).ctrl.get.ext.op1
          io.b_hfu.get(h).ctrl.get.op2 := io.b_in(bp).ctrl.get.ext.op2
          io.b_hfu.get(h).ctrl.get.op3 := io.b_in(bp).ctrl.get.ext.op3
          io.b_hfu.get(h).ctrl.get.hfs1 := io.b_in(bp).ctrl.get.ext.rs1
          io.b_hfu.get(h).ctrl.get.hfs2 := io.b_in(bp).ctrl.get.ext.rs2
          io.b_hfu.get(h).ctrl.get.wb := io.b_in(bp).ctrl.get.gpr.en

          io.b_hfu.get(h).data.get.s2 := w_ex0(bp).data.get.s2
          io.b_hfu.get(h).data.get.s3 := w_ex0(bp).data.get.s3
        }
      }
    }
  } else {
    for (bp <- 0 until p.nBackPort) {
      w_ex0_hfu_av(bp) := false.B
    }
  }

  // ------------------------------
  //              LSU
  // ------------------------------
  for (bp <- 0 until p.nBackPort) {
    w_ex0_wait_st(bp) := io.b_in(bp).ctrl.get.lsu.st & w_ex0_pred_exc(bp)
    w_ex0_wait_lsu(bp) := io.b_in(bp).ctrl.get.lsu.use & (~io.b_lsu(bp).ready | w_ex0_wait_st(bp))    
    
    if (p.nExStage > 1) {
      io.b_lsu(bp).valid := w_ex0(bp).valid & io.b_in(bp).ctrl.get.lsu.use & ~(w_ex0_wait_bp(bp) | w_ex0_wait_unit(bp) | w_ex0_lock(bp) | w_ex0_wait_st(bp))
    } else {
      io.b_lsu(bp).valid := w_ex0(bp).valid & io.b_in(bp).ctrl.get.lsu.use & ~(w_ex0_wait_bp(bp) | w_ex0_wait_unit(bp) | w_ex0_lock(bp) | w_ex0_wait_st(bp) | w_ex1_wait(bp) | w_ex2_wait(bp))
    }
    io.b_lsu(bp).ctrl.get.hart := io.b_in(bp).ctrl.get.info.hart
    io.b_lsu(bp).ctrl.get.uop := io.b_in(bp).ctrl.get.lsu.uop
    io.b_lsu(bp).ctrl.get.amo := io.b_in(bp).ctrl.get.lsu.amo
    io.b_lsu(bp).ctrl.get.size := io.b_in(bp).ctrl.get.lsu.size
    io.b_lsu(bp).ctrl.get.sign := io.b_in(bp).ctrl.get.lsu.sign
    io.b_lsu(bp).ctrl.get.trap := DontCare
    io.b_lsu(bp).ctrl.get.trap.valid := false.B
    io.b_lsu(bp).data.get := io.b_in(bp).data.get.s3
    
    if (p.nAlu == p.nBackPort) {      
      io.b_lsu(bp).ctrl.get.addr := m_alu(bp).io.o_add     
    } else {
      io.b_lsu(bp).ctrl.get.addr := m_alu(0).io.o_add
      for (a <- 0 until p.nAlu) {
        when (io.b_in(bp).ctrl.get.int.port === a.U) {
          io.b_lsu(bp).ctrl.get.addr := m_alu(a).io.o_add
        }
      }      
    }
  }

  // ------------------------------
  //             FLUSH
  // ------------------------------
  for (bp0 <- 0 until p.nBackPort) {
    w_ex0_flush(bp0) := w_back_flush(bp0)
    for (bp1 <- 0 until p.nBackPort) {
      if (p.useBranchReg) {
        if (p.nExStage > 2) {
          when (r_flush_pipe(bp1) & (w_ex0(bp0).ctrl.get.hart === m_ex1(bp1).io.o_val.ctrl.get.info.hart)) {
            w_ex0_flush(bp0) := true.B
          }
        } else {
          when (r_flush_pipe(bp1) & (w_ex0(bp0).ctrl.get.hart === m_out(bp1).io.o_val.ctrl.get.info.hart)) {
            w_ex0_flush(bp0) := true.B
          }
        }
      } else {
        if ((p.nExStage > 1) || (bp0 > bp1)) {
          when (w_flush_pipe(bp1) & (w_ex0(bp0).ctrl.get.hart === w_ex1(bp1).ctrl.get.info.hart)) {
            w_ex0_flush(bp0) := true.B
          }
        }
      }
    }
  }

  // ------------------------------
  //           REGISTERS
  // ------------------------------
  // Wait next stages
  for (bp0 <- 0 until p.nBackPort) {
    if (p.nExStage > 1) {
      w_ex0_lock(bp0) := ~m_ex0(bp0).io.b_in.ready
      for (bp1 <- 0 until p.nBackPort) {
        if (bp1 != bp0) {
          when (~m_ex0(bp1).io.b_in.ready & (m_ex0(bp1).io.o_val.ctrl.get.info.hart === io.b_in(bp0).ctrl.get.info.hart)) {
            w_ex0_lock(bp0) := true.B
          }
        }
      }
    } else {
      w_ex0_lock(bp0) := w_ex2_lock(bp0)
      for (bp1 <- 0 until p.nBackPort) {
        if (bp1 != bp0) {
          when (~m_out(bp1).io.b_in.ready & (m_out(bp1).io.o_val.ctrl.get.info.hart === io.b_in(bp0).ctrl.get.info.hart)) {
            w_ex0_lock(bp0) := true.B
          }
        }
      }
    }
  }

  // Wait other ports
  for (bp0 <- 0 until p.nBackPort) {
    w_ex0_wait_bp(bp0) := false.B
    for (bp1 <- 0 until p.nBackPort) {
      if (bp1 != bp0) {
        when (io.b_in(bp1).valid & ~w_ex0_flush(bp1) & (io.b_in(bp1).ctrl.get.info.hart === io.b_in(bp0).ctrl.get.info.hart) & (w_ex0_wait_unit(bp1) | w_ex0_wait_lsu(bp1))) {
          w_ex0_wait_bp(bp0) := true.B
        }
      }
    }
  }

  // Wait
  for (bp <- 0 until p.nBackPort) {
    w_ex0_wait(bp) := w_ex0_wait_bp(bp) | w_ex0_wait_lsu(bp) | w_ex0_wait_unit(bp) | w_ex0_lock(bp)
  }
  
  // Update registers
  for (bp <- 0 until p.nBackPort) {
    if (p.nExStage > 1) {
      if (p.useField) {
        m_ex0(bp).io.i_flush := io.b_back.get(bp).flush
      } else {
        m_ex0(bp).io.i_flush := false.B
      } 

      m_ex0(bp).io.b_in.valid := io.b_in(bp).valid & ~w_ex0_wait(bp)& ~w_ex0_flush(bp)

      m_ex0(bp).io.b_in.ctrl.get.info := io.b_in(bp).ctrl.get.info
      m_ex0(bp).io.b_in.ctrl.get.trap := io.b_in(bp).ctrl.get.trap

      m_ex0(bp).io.b_in.ctrl.get.int := io.b_in(bp).ctrl.get.int
      m_ex0(bp).io.b_in.ctrl.get.lsu.use := io.b_in(bp).ctrl.get.lsu.use
      m_ex0(bp).io.b_in.ctrl.get.lsu.load := io.b_in(bp).ctrl.get.lsu.ld
      m_ex0(bp).io.b_in.ctrl.get.csr := io.b_in(bp).ctrl.get.csr
      m_ex0(bp).io.b_in.ctrl.get.gpr := io.b_in(bp).ctrl.get.gpr

      m_ex0(bp).io.b_in.ctrl.get.ext := io.b_in(bp).ctrl.get.ext
      m_ex0(bp).io.b_in.ctrl.get.hpc := io.b_in(bp).ctrl.get.hpc

      m_ex0(bp).io.b_in.data.get.res := DontCare
      m_ex0(bp).io.b_in.data.get.s1 := io.b_in(bp).data.get.s1
      m_ex0(bp).io.b_in.data.get.s3 := io.b_in(bp).data.get.s3      

      w_ex1_wait_unit(bp) := (m_ex0(bp).io.b_out.ctrl.get.int.unit === INTUNIT.ALU) | (m_ex0(bp).io.b_out.ctrl.get.int.unit === INTUNIT.BRU)

      if (p.nExStage > 2) {
        m_ex0(bp).io.b_out.ready := w_ex1_flush(bp) | ~w_ex1_wait(bp)
      } else {
        m_ex0(bp).io.b_out.ready := w_ex1_flush(bp) | (~w_ex1_wait(bp) & ~w_ex2_wait(bp))
      }
      w_ex1(bp).valid := m_ex0(bp).io.b_out.valid
      w_ex1(bp).ctrl.get := m_ex0(bp).io.b_out.ctrl.get
      w_ex1(bp).data.get := m_ex0(bp).io.b_out.data.get

      io.b_in(bp).ready := w_ex0_flush(bp) | ~w_ex0_wait(bp)
    } else {
      m_ex0(bp).io.i_flush := false.B
      m_ex0(bp).io.b_in := DontCare
      m_ex0(bp).io.b_in.valid := false.B
      m_ex0(bp).io.b_out.ready := false.B

      w_ex1_wait_unit(bp) := (io.b_in(bp).ctrl.get.int.unit === INTUNIT.ALU) | (io.b_in(bp).ctrl.get.int.unit === INTUNIT.BRU)

      w_ex1(bp).valid := io.b_in(bp).valid & ~(w_ex0_wait_bp(bp) | w_ex0_wait_lsu(bp) | w_ex0_wait_unit(bp) | w_ex0_flush(bp))

      w_ex1(bp).ctrl.get.info := io.b_in(bp).ctrl.get.info
      w_ex1(bp).ctrl.get.trap := io.b_in(bp).ctrl.get.trap

      w_ex1(bp).ctrl.get.int := io.b_in(bp).ctrl.get.int
      w_ex1(bp).ctrl.get.lsu.use := io.b_in(bp).ctrl.get.lsu.use
      w_ex1(bp).ctrl.get.lsu.load := io.b_in(bp).ctrl.get.lsu.ld
      w_ex1(bp).ctrl.get.csr := io.b_in(bp).ctrl.get.csr
      w_ex1(bp).ctrl.get.gpr := io.b_in(bp).ctrl.get.gpr  

      w_ex1(bp).ctrl.get.ext := io.b_in(bp).ctrl.get.ext   
      w_ex1(bp).ctrl.get.hpc := io.b_in(bp).ctrl.get.hpc   
      
      w_ex1(bp).data.get.res := DontCare
      w_ex1(bp).data.get.s1 := io.b_in(bp).data.get.s1
      w_ex1(bp).data.get.s3 := io.b_in(bp).data.get.s3

      io.b_in(bp).ready := w_ex0_flush(bp) | w_ex1_flush(bp) | (~w_ex0_wait(bp) & ~w_ex1_wait(bp) & ~w_ex2_wait(bp))
    }
  }

  // ******************************
  //              EX1
  // ******************************  
  // ------------------------------
  //          NEW BRANCH
  // ------------------------------
  for (bp <- 0 until p.nBackPort) {
    w_ex1_br_new(bp) := DontCare
    w_ex1_br_new(bp).valid := false.B
    w_flush_pipe(bp) := false.B
  }

  // ------------------------------
  //          UNITS: ALU
  // ------------------------------
  for (a <- 0 until p.nAlu) {
    m_alu(a).io.b_port.ack.ready := false.B

    if (p.nAlu == p.nBackPort) {
      if (p.nExStage > 2) {
        m_alu(a).io.b_port.ack.ready := w_ex1(a).valid & (w_ex1(a).ctrl.get.int.unit === INTUNIT.ALU) & (w_ex1_flush(a) | (~w_ex1_wait_bp(a) & ~w_ex1_lock(a)))  
      } else {
        m_alu(a).io.b_port.ack.ready := w_ex1(a).valid & (w_ex1(a).ctrl.get.int.unit === INTUNIT.ALU) & (w_ex1_flush(a) | (~w_ex1_wait_bp(a) & ~w_ex1_lock(a) & ~w_ex2_wait(a)))  
      }

      w_ex1_wait_unit(a) := ~m_alu(a).io.b_port.ack.valid & (w_ex1(a).ctrl.get.int.unit === INTUNIT.ALU)
      w_ex1(a).data.get.res := m_alu(a).io.b_port.ack.data.get
    } else {
      for (bp <- 0 until p.nBackPort) {
        when ((w_ex1(bp).ctrl.get.int.unit === INTUNIT.ALU) & (w_ex1(bp).ctrl.get.int.port === a.U)) {
          when (w_ex1(bp).valid) {
            if (p.nExStage > 2) {
              m_alu(a).io.b_port.ack.ready := w_ex1_flush(bp) | (~w_ex1_wait_bp(bp) & ~w_ex1_lock(bp))
            } else {
              m_alu(a).io.b_port.ack.ready := w_ex1_flush(bp) | (~w_ex1_wait_bp(bp) & ~w_ex1_lock(bp) & ~w_ex2_wait(bp))
            } 
          }       

          w_ex1_wait_unit(bp) := ~m_alu(a).io.b_port.ack.valid
          w_ex1(bp).data.get.res := m_alu(a).io.b_port.ack.data.get 
        }
      }
    }
  }  

  // ------------------------------
  //          UNITS: BRU
  // ------------------------------
  for (b <- 0 until p.nBru) {
    m_bru(b).io.b_port.ack.ready := false.B

    for (bp <- 0 until p.nBackPort) {
      when ((w_ex1(bp).ctrl.get.int.unit === INTUNIT.BRU) & (w_ex1(bp).ctrl.get.info.hart === b.U)) {
         w_ex1_wait_unit(bp) := ~m_bru(b).io.b_port.ack.valid
      }
      
      when (w_ex1(bp).valid & (w_ex1(bp).ctrl.get.int.unit === INTUNIT.BRU) & (w_ex1(bp).ctrl.get.info.hart === b.U)) {
        if (p.nExStage > 2) {
          m_bru(b).io.b_port.ack.ready := w_ex1_flush(bp) | (~w_ex1_wait_bp(bp) & ~w_ex1_lock(bp)) 

          w_ex1_br_new(bp).valid := m_bru(b).io.b_port.ack.valid & m_bru(b).io.o_br_new.valid & ~w_ex1_flush(bp) & ~w_ex1_wait_bp(bp) & ~w_ex1_lock(bp)
          w_flush_pipe(bp) := m_bru(b).io.b_port.ack.valid & m_bru(b).io.o_flush & ~w_ex1_flush(bp) & ~w_ex1_wait_bp(bp) & ~w_ex1_lock(bp)
        } else {
          m_bru(b).io.b_port.ack.ready := w_ex1_flush(bp) | (~w_ex1_wait_bp(bp) & ~w_ex1_lock(bp) & ~w_ex2_wait(bp)) 

          w_ex1_br_new(bp).valid := m_bru(b).io.b_port.ack.valid & m_bru(b).io.o_br_new.valid & ~w_ex1_flush(bp) & ~w_ex1_wait_bp(bp) & ~w_ex1_lock(bp) & ~w_ex2_wait(bp)
          w_flush_pipe(bp) := m_bru(b).io.b_port.ack.valid & m_bru(b).io.o_flush & ~w_ex1_flush(bp) & ~w_ex1_wait_bp(bp) & ~w_ex1_lock(bp) & ~w_ex2_wait(bp)
        }
                
        w_ex1(bp).data.get.res := m_bru(b).io.b_port.ack.data.get      
        w_ex1_br_new(bp).addr := m_bru(b).io.o_br_new.addr
        w_ex1(bp).ctrl.get.hpc.mispred := m_bru(b).io.b_port.ack.valid & m_bru(b).io.o_br_new.valid
      }
    }    
  }  

  // ------------------------------
  //         UNITS: MULDIV
  // ------------------------------
  if (p.useExtM) {
    for (m <- 0 until p.nMulDiv) {
      m_muldiv.get(m).io.b_port.ack.ready := false.B

      if (p.nMulDiv == p.nBackPort) {
        when (w_ex1_flush(m)) {
          m_muldiv.get(m).io.i_flush := true.B
        }
      } else {
        for (bp <- 0 until p.nBackPort) {
          when (w_ex1(bp).valid & (w_ex1(bp).ctrl.get.int.unit === INTUNIT.MULDIV) & (m.U === w_ex1(bp).ctrl.get.int.port) & w_ex1_flush(bp)) {
            m_muldiv.get(m).io.i_flush := true.B
          }
        }
      }
    }  
  }

  // ------------------------------
  //             BRANCH
  // ------------------------------
  // New branch  
  val init_br_new = Wire(Vec(p.nHart, new BranchBus(p.nAddrBit)))

  for (h <- 0 until p.nHart) {
    init_br_new(h) := DontCare
    init_br_new(h).valid := false.B
  }  

  val r_br_new = RegInit(init_br_new)
  val w_br_new = Wire(Vec(p.nHart, new BranchBus(p.nAddrBit)))

  r_br_new := w_br_new

  for (h <- 0 until p.nHart) {
    w_br_new(h) := DontCare
    w_br_new(h).valid := false.B

    for (bp <- (p.nBackPort - 1) to 0 by -1) {
      when (w_ex1_br_new(bp).valid & (h.U === w_ex1(bp).ctrl.get.info.hart)) {
        w_br_new(h) := w_ex1_br_new(bp)
      }
    }    
  }

  if (p.useBranchReg) {
    io.o_br_new := r_br_new
  } else {
    io.o_br_new := w_br_new
  }


  // Branch info
  val init_br_info = Wire(Vec(p.nHart, new BranchInfoBus(p.nAddrBit)))

  for (h <- 0 until p.nHart) {
    init_br_info(h) := DontCare
    init_br_info(h).valid := false.B
  }  

  val r_br_info = RegInit(init_br_info)

  for (h <- 0 until p.nHart) {
    r_br_info(h) := m_bru(h).io.o_br_info
    io.o_br_info(h) := r_br_info(h)
  } 

  // ------------------------------
  //             FLUSH
  // ------------------------------
  r_flush_pipe := w_flush_pipe

  for (h <- 0 until p.nHart) {
    io.o_flush(h) := false.B
    for (bp <- 0 until p.nBackPort) {
      if (p.useBranchReg) {
        if (p.nExStage > 2) {
          when (r_flush_pipe(bp) & (h.U === m_ex1(bp).io.o_val.ctrl.get.info.hart)) {
            io.o_flush(h) := true.B
          }
        } else {
          when (r_flush_pipe(bp) & (h.U === m_out(bp).io.o_val.ctrl.get.info.hart)) {
            io.o_flush(h) := true.B
          }
        }
      } else {
        when (w_flush_pipe(bp) & (h.U === w_ex1(bp).ctrl.get.info.hart)) {
          io.o_flush(h) := true.B
        }
      }
    }
  } 

  for (bp0 <- 0 until p.nBackPort) {
    w_ex1_flush(bp0) := w_back_flush(bp0)

    for (bp1 <- 0 until p.nBackPort) {
      if (p.useBranchReg) {
        if (p.nExStage > 2) {
          when (r_flush_pipe(bp1) & (w_ex1(bp0).ctrl.get.info.hart === m_ex1(bp1).io.o_val.ctrl.get.info.hart)) {
            w_ex1_flush(bp0) := true.B
          }
        } else if (p.nExStage > 1) {
          when (r_flush_pipe(bp1) & (w_ex1(bp0).ctrl.get.info.hart === m_out(bp1).io.o_val.ctrl.get.info.hart)) {
            w_ex1_flush(bp0) := true.B
          }
        }
      } else if (bp0 > bp1) { 
        when (w_flush_pipe(bp1) & (w_ex1(bp0).ctrl.get.info.hart === w_ex1(bp1).ctrl.get.info.hart)) {
          w_ex1_flush(bp0) := true.B
        }
      }
    }
  } 

  // ------------------------------
  //           REGISTERS
  // ------------------------------
  // Wait next stages
  for (bp0 <- 0 until p.nBackPort) {
    if (p.nExStage > 2) {
      w_ex1_lock(bp0) := ~m_ex1(bp0).io.b_in.ready
      for (bp1 <- 0 until p.nBackPort) {
        if (bp1 != bp0) {
          when (~m_ex1(bp1).io.b_in.ready & (m_ex1(bp1).io.o_val.ctrl.get.info.hart === w_ex1(bp0).ctrl.get.info.hart)) {
            w_ex1_lock(bp0) := true.B
          }
        }
      }
    } else {
      w_ex1_lock(bp0) := w_ex2_lock(bp0)
      for (bp1 <- 0 until p.nBackPort) {
        if (bp1 != bp0) {
          when (~m_out(bp1).io.b_in.ready & (m_out(bp1).io.o_val.ctrl.get.info.hart === w_ex1(bp0).ctrl.get.info.hart)) {
            w_ex1_lock(bp0) := true.B
          }
        }
      }
    }
  }

  // Wait other ports
  for (bp0 <- 0 until p.nBackPort) {
    w_ex1_wait_bp(bp0) := false.B
    for (bp1 <- 0 until bp0) {
      when (w_ex1(bp1).valid & (w_ex1(bp1).ctrl.get.info.hart === w_ex1(bp0).ctrl.get.info.hart) & w_ex1_wait_unit(bp1)) {
        w_ex1_wait_bp(bp0) := true.B
      }
    }
  }

  // Wait
  for (bp <- 0 until p.nBackPort) {
    w_ex1_wait(bp) := ~w_ex1_flush(bp) & (w_ex1_wait_bp(bp) | w_ex1_wait_unit(bp) | w_ex1_lock(bp))
  }
  
  // Update registers
  for (bp <- 0 until p.nBackPort) {
    if (p.nExStage > 2) {
      if (p.useField) {
        m_ex1(bp).io.i_flush := io.b_back.get(bp).flush
      } else {
        m_ex1(bp).io.i_flush := false.B
      } 

      m_ex1(bp).io.b_in.valid := w_ex1(bp).valid & ~w_ex1_wait(bp) & ~w_ex1_flush(bp)
      m_ex1(bp).io.b_in.ctrl.get := w_ex1(bp).ctrl.get    
      m_ex1(bp).io.b_in.data.get := w_ex1(bp).data.get

      w_ex2_wait_unit(bp) := (m_ex1(bp).io.o_val.ctrl.get.int.unit === INTUNIT.MULDIV)
      
      m_ex1(bp).io.b_out.ready := w_ex2_flush(bp) | ~w_ex2_wait(bp)
      w_ex2(bp).valid := m_ex1(bp).io.b_out.valid
      w_ex2(bp).ctrl.get := m_ex1(bp).io.b_out.ctrl.get
      w_ex2(bp).data.get := m_ex1(bp).io.b_out.data.get
    } else {
      m_ex1(bp).io.i_flush := false.B
      m_ex1(bp).io.b_in := DontCare
      m_ex1(bp).io.b_in.valid := false.B
      m_ex1(bp).io.b_out.ready := false.B

      w_ex2_wait_unit(bp) := (w_ex1(bp).ctrl.get.int.unit === INTUNIT.MULDIV)

      w_ex2(bp).valid := w_ex1(bp).valid & ~(w_ex1_wait_bp(bp) | w_ex1_wait_unit(bp) | w_ex1_flush(bp))
      w_ex2(bp).ctrl.get := w_ex1(bp).ctrl.get
      w_ex2(bp).data.get := w_ex1(bp).data.get
    }
  }

  // ******************************
  //              EX2
  // ******************************
  // ------------------------------
  //         UNITS: MULDIV
  // ------------------------------
  if (p.useExtM) {
    for (m <- 0 until p.nMulDiv) {
      m_muldiv.get(m).io.b_port.ack.ready := false.B

      if (p.nMulDiv == p.nBackPort) {
        m_muldiv.get(m).io.b_port.ack.ready := w_ex2(m).valid & (w_ex2(m).ctrl.get.int.unit === INTUNIT.MULDIV) & ~w_ex2_wait_bp(m) & ~w_ex2_lock(m)        

        w_ex2_wait_unit(m) := ~m_muldiv.get(m).io.b_port.ack.valid & (w_ex2(m).ctrl.get.int.unit === INTUNIT.MULDIV)
        w_ex2(m).data.get.res := m_muldiv.get(m).io.b_port.ack.data.get
      } else {
        for (bp <- 0 until p.nBackPort) {
          when ((w_ex2(bp).ctrl.get.int.unit === INTUNIT.MULDIV) & (w_ex2(bp).ctrl.get.int.port === m.U)) {
            when (w_ex2(bp).valid) {
              m_muldiv.get(m).io.b_port.ack.ready := ~w_ex2_wait_bp(bp) & ~w_ex2_lock(bp)
            }

            w_ex2_wait_unit(bp) := ~m_muldiv.get(m).io.b_port.ack.valid
            w_ex2(bp).data.get.res := m_muldiv.get(m).io.b_port.ack.data.get
          }
        }
      }
    }  
  }

  // ------------------------------
  //             FLUSH
  // ------------------------------
  for (bp <- 0 until p.nBackPort) {
    w_ex2_flush(bp) := w_back_flush(bp)
  } 

  // ------------------------------
  //           REGISTERS
  // ------------------------------
  // Wait next stages
  for (bp0 <- 0 until p.nBackPort) {
    w_ex2_lock(bp0) := ~m_out(bp0).io.b_in.ready
    for (bp1 <- 0 until p.nBackPort) {
      if (bp1 != bp0) {
        when (~m_out(bp1).io.b_in.ready & (m_out(bp1).io.o_val.ctrl.get.info.hart === w_ex2(bp0).ctrl.get.info.hart)) {
          w_ex2_lock(bp0) := true.B
        }
      }
    }
  }

  // Wait other ports
  for (bp0 <- 0 until p.nBackPort) {
    w_ex2_wait_bp(bp0) := false.B
    for (bp1 <- 0 until bp0) {
      when (w_ex2(bp1).valid & ~w_ex2_flush(bp1) & (w_ex2(bp1).ctrl.get.info.hart === w_ex2(bp0).ctrl.get.info.hart) & w_ex2_wait_unit(bp1)) {
        w_ex2_wait_bp(bp0) := true.B
      }
    }
  }

  // Wait
  for (bp <- 0 until p.nBackPort) {
    w_ex2_wait(bp) := ~w_ex2_flush(bp) & (w_ex2_wait_bp(bp) | w_ex2_wait_unit(bp) | w_ex2_lock(bp))
  }

  // Update register
  for (bp <- 0 until p.nBackPort) {  
    if (p.useField) {
      m_out(bp).io.i_flush := io.b_back.get(bp).flush
    } else {
      m_out(bp).io.i_flush := false.B
    } 
      
    m_out(bp).io.b_in.valid := w_ex2(bp).valid & ~w_ex2_wait(bp) & ~w_ex2_flush(bp)

    m_out(bp).io.b_in.ctrl.get.info := w_ex2(bp).ctrl.get.info
    m_out(bp).io.b_in.ctrl.get.trap := w_ex2(bp).ctrl.get.trap

    m_out(bp).io.b_in.ctrl.get.lsu := w_ex2(bp).ctrl.get.lsu
    m_out(bp).io.b_in.ctrl.get.csr := w_ex2(bp).ctrl.get.csr
    m_out(bp).io.b_in.ctrl.get.gpr := w_ex2(bp).ctrl.get.gpr

    m_out(bp).io.b_in.ctrl.get.ext := w_ex2(bp).ctrl.get.ext
    m_out(bp).io.b_in.ctrl.get.hpc := w_ex2(bp).ctrl.get.hpc

    m_out(bp).io.b_in.data.get := w_ex2(bp).data.get
  }

  // ------------------------------
  //            OUTPUTS
  // ------------------------------
  for (bp <- 0 until p.nBackPort) {
    io.b_out(bp) <> m_out(bp).io.b_out
  }

  // ******************************
  //            LSU STORE
  // ******************************
  for (h <- 0 until p.nHart) {
    for (bp <- 0 until p.nBackPort) {
      if (p.nExStage > 1) {
        w_ex1_exc_on(h)(bp) := w_ex1(bp).valid & w_ex1(bp).ctrl.get.trap.gen & ~w_ex1(bp).ctrl.get.lsu.use & (h.U === w_ex1(bp).ctrl.get.info.hart)
      } else {
        w_ex1_exc_on(h)(bp) := false.B
      }

      if (p.nExStage > 2) {
        w_ex2_exc_on(h)(bp) := w_ex2(bp).valid & w_ex2(bp).ctrl.get.trap.gen & ~w_ex2(bp).ctrl.get.lsu.use & (h.U === w_ex2(bp).ctrl.get.info.hart)
      } else {
        w_ex2_exc_on(h)(bp) := false.B
      }
    }
  }

  // Potential predecessor exception
  for (bp0 <- 0 until p.nBackPort) {
    w_ex0_pred_exc(bp0) := false.B
    for (h <- 0 until p.nHart) {
      when (h.U === io.b_in(bp0).ctrl.get.info.hart) {
        w_ex0_pred_exc(bp0) := io.i_exc_on(h) | w_ex1_exc_on(h).asUInt.orR | w_ex2_exc_on(h).asUInt.orR
      }
    }

    for (bp1 <- 0 until bp0) {
      when (io.b_in(bp1).valid & io.b_in(bp1).ctrl.get.trap.gen & ~io.b_in(bp1).ctrl.get.lsu.use & (io.b_in(bp0).ctrl.get.info.hart === io.b_in(bp1).ctrl.get.info.hart)) {
        w_ex0_pred_exc(bp0) := true.B
      }
    }
  }

  // ******************************
  //       BYPASS CONNECTIONS
  // ******************************
  var v_byp: Int = 0

  // Ex2
  for (bp <- 0 until p.nBackPort) {
    io.o_byp(bp).valid := w_ex2(bp).valid & w_ex2(bp).ctrl.get.gpr.en
    io.o_byp(bp).hart := w_ex2(bp).ctrl.get.info.hart
    io.o_byp(bp).addr := w_ex2(bp).ctrl.get.gpr.addr
    io.o_byp(bp).ready := ~(w_ex2(bp).ctrl.get.lsu.load | (w_ex2(bp).ctrl.get.ext.ext =/= EXT.NONE) | w_ex2(bp).ctrl.get.csr.read | w_ex2_wait_unit(bp))
    io.o_byp(bp).data := w_ex2(bp).data.get.res 
  }
  v_byp = p.nBackPort

  // Ex1
  if (p.nExStage > 2) {    
    for (bp <- 0 until p.nBackPort) {
      io.o_byp(v_byp + bp).valid := w_ex1(bp).valid & w_ex1(bp).ctrl.get.gpr.en
      io.o_byp(v_byp + bp).hart := w_ex1(bp).ctrl.get.info.hart
      io.o_byp(v_byp + bp).addr := w_ex1(bp).ctrl.get.gpr.addr
      io.o_byp(v_byp + bp).ready := ~(w_ex1(bp).ctrl.get.lsu.load | (w_ex1(bp).ctrl.get.ext.ext =/= EXT.NONE) | w_ex1(bp).ctrl.get.csr.read | w_ex1_wait_unit(bp) | (io.b_in(bp).ctrl.get.int.unit === INTUNIT.MULDIV))
      io.o_byp(v_byp + bp).data := w_ex1(bp).data.get.res
    }

    v_byp = v_byp + p.nBackPort
  }

  // Ex0
  if (p.nExStage > 1) {
    for (bp <- 0 until p.nBackPort) {
      io.o_byp(v_byp + bp).valid := io.b_in(bp).valid & io.b_in(bp).ctrl.get.gpr.en
      io.o_byp(v_byp + bp).hart := io.b_in(bp).ctrl.get.info.hart
      io.o_byp(v_byp + bp).addr := io.b_in(bp).ctrl.get.gpr.addr
      io.o_byp(v_byp + bp).ready := (io.b_in(bp).ctrl.get.int.unit === INTUNIT.ALU) & ~io.b_in(bp).ctrl.get.lsu.ld  

      if (p.nAlu == p.nBackPort) {
        io.o_byp(v_byp + bp).data := m_alu(bp).io.o_byp
      } else {
        io.o_byp(v_byp + bp).data := DontCare
        for (a <- 0 until p.nAlu) {
          when (a.U === io.b_in(bp).ctrl.get.int.port) {
            io.o_byp(v_byp + bp).data := m_alu(a).io.o_byp
          }
        }
      }       
    }
  }

  // ******************************
  //             STAGE
  // ******************************
  for (bp <- 0 until p.nBackPort) {
    io.o_stage(0)(bp).valid := io.b_in(bp).valid
    io.o_stage(0)(bp).hart := io.b_in(bp).ctrl.get.info.hart
    io.o_stage(0)(bp).pc := io.b_in(bp).ctrl.get.info.pc
    io.o_stage(0)(bp).instr := io.b_in(bp).ctrl.get.info.instr
    io.o_stage(0)(bp).exc_gen := io.b_in(bp).ctrl.get.trap.gen
    io.o_stage(0)(bp).end := io.b_in(bp).valid & io.b_in(bp).ctrl.get.info.end

    if (p.nExStage > 1) {
      io.o_stage(1)(bp).valid := m_ex0(bp).io.b_out.valid
      io.o_stage(1)(bp).hart := m_ex0(bp).io.b_out.ctrl.get.info.hart
      io.o_stage(1)(bp).pc := m_ex0(bp).io.b_out.ctrl.get.info.pc
      io.o_stage(1)(bp).instr := m_ex0(bp).io.b_out.ctrl.get.info.instr
      io.o_stage(1)(bp).exc_gen := m_ex0(bp).io.b_out.ctrl.get.trap.gen
      io.o_stage(1)(bp).end := m_ex0(bp).io.b_out.valid & m_ex0(bp).io.b_out.ctrl.get.info.end
    }

    if (p.nExStage > 2) {
      io.o_stage(2)(bp).valid := m_ex1(bp).io.b_out.valid
      io.o_stage(2)(bp).hart := m_ex1(bp).io.b_out.ctrl.get.info.hart
      io.o_stage(2)(bp).pc := m_ex1(bp).io.b_out.ctrl.get.info.pc
      io.o_stage(2)(bp).instr := m_ex1(bp).io.b_out.ctrl.get.info.instr
      io.o_stage(2)(bp).exc_gen := m_ex1(bp).io.b_out.ctrl.get.trap.gen
      io.o_stage(2)(bp).end := m_ex1(bp).io.b_out.valid & m_ex1(bp).io.b_out.ctrl.get.info.end
    }
  }

  // ******************************
  //             FIELD
  // ******************************
  if (p.useField) {
    for (a <- 0 until p.nAlu) {
      io.b_alu.get(a).free := m_alu(a).io.o_free
    }

    if (p.nMulDiv > 0) {
      for (m <- 0 until p.nMulDiv) {
        io.b_muldiv.get(m).free := m_muldiv.get(m).io.o_free
      }
    }

    for (h <- 0 until p.nHart) {
      io.b_hart.get(h).free := m_bru(h).io.o_free
    }

    for (bp <- 0 until p.nBackPort) {
      io.b_back.get(bp).free := ~m_out(bp).io.o_val.valid & ~m_ex0(bp).io.o_val.valid & ~m_ex1(bp).io.o_val.valid
    }    
  } 

  // ******************************
  //             DEBUG
  // ******************************
  if (p.debug) {
    // ------------------------------
    //         DATA FOOTPRINT
    // ------------------------------
    val w_dfp = Wire(Vec(p.nExStage, Vec(p.nBackPort, new Bundle {
      val pc = UInt(p.nAddrBit.W)
      val instr = UInt(p.nInstrBit.W)

      val s1 = UInt(p.nDataBit.W)
      val s3 = UInt(p.nDataBit.W)
      val res = UInt(p.nDataBit.W)
    })))

    for (bp <- 0 until p.nBackPort) {
      w_dfp(p.nExStage - 1)(bp).pc := m_out(bp).io.o_reg.ctrl.get.info.pc
      w_dfp(p.nExStage - 1)(bp).instr := m_out(bp).io.o_reg.ctrl.get.info.instr
      w_dfp(p.nExStage - 1)(bp).s1 := m_out(bp).io.o_reg.data.get.s1
      w_dfp(p.nExStage - 1)(bp).s3 := m_out(bp).io.o_reg.data.get.s3
      w_dfp(p.nExStage - 1)(bp).res := m_out(bp).io.o_reg.data.get.res


      if (p.nExStage > 1) {    
        w_dfp(0)(bp).pc := m_ex0(bp).io.o_reg.ctrl.get.info.pc
        w_dfp(0)(bp).instr := m_ex0(bp).io.o_reg.ctrl.get.info.instr
        w_dfp(0)(bp).s1 := m_ex0(bp).io.o_reg.data.get.s1
        w_dfp(0)(bp).s3 := m_ex0(bp).io.o_reg.data.get.s3
        w_dfp(0)(bp).res := m_ex0(bp).io.o_reg.data.get.res             
      }

      if (p.nExStage > 2) {    
        w_dfp(1)(bp).pc := m_ex1(bp).io.o_reg.ctrl.get.info.pc
        w_dfp(1)(bp).instr := m_ex1(bp).io.o_reg.ctrl.get.info.instr
        w_dfp(1)(bp).s1 := m_ex1(bp).io.o_reg.data.get.s1
        w_dfp(1)(bp).s3 := m_ex1(bp).io.o_reg.data.get.s3
        w_dfp(1)(bp).res := m_ex1(bp).io.o_reg.data.get.res        
      }
    }     
    
    dontTouch(w_dfp)

    // ------------------------------
    //       EXECUTION TRACKER
    // ------------------------------
    for (h <- 0 until p.nHart) {
      if (p.useChamp) {
        for (bp <- 0 until p.nBackPort) {
          when (w_ex0(bp).valid & w_ex0_hfu_av(bp) & (io.b_in(bp).ctrl.get.ext.ext === EXT.HFU) & (h.U === w_ex0(bp).ctrl.get.hart)) {
            io.b_hfu.get(h).ctrl.get.etd.get := io.b_in(bp).ctrl.get.etd.get
          }
        }
      }      
    }

    for (bp <- 0 until p.nBackPort) {
      if (p.nExStage > 1) {
        m_ex0(bp).io.b_in.ctrl.get.etd.get := io.b_in(bp).ctrl.get.etd.get
      } else {
        w_ex1(bp).ctrl.get.etd.get := io.b_in(bp).ctrl.get.etd.get
      }
      
      m_out(bp).io.b_in.ctrl.get.etd.get := w_ex2(bp).ctrl.get.etd.get
    }
  }
}

object ExStage extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new ExStage(BackConfigBase), args)
}