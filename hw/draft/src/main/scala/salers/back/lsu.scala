/*
 * File: lsu.scala                                                             *
 * Created Date: 2023-03-08 01:51:25 pm                                        *
 * Author: Mathieu Escouteloup                                                 *
 * -----                                                                       *
 * Last Modified: 2023-04-11 06:50:35 pm                                       *
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
import herd.common.mem.mb4s._
import herd.common.mem.mb4s.{OP => LSUUOP, AMO => LSUAMO}
import herd.common.isa.riscv._
import herd.common.isa.priv.{EXC => PRIVEXC}
import herd.common.isa.champ.{EXC => CHAMPEXC}
import herd.core.aubrac.common._
import herd.core.aubrac.back.{LSUSIZE,LSUSIGN}


class Lsu (p: BackParams) extends Module {
  val io = IO(new Bundle {
    val b_hart = if (p.useField) Some(Vec(p.nHart, new RsrcIO(p.nHart, p.nField, p.nHart))) else None

    val i_flush = Input(Vec(p.nHart, Bool()))

    val b_req = Vec(p.nBackPort, Flipped(new GenRVIO(p, new LsuReqCtrlBus(p), UInt(p.nDataBit.W))))

    val o_stop = Output(Vec(p.nHart, Bool()))

    val b_d0mem = new Mb4sIO(p.pL0D0Bus)
    val b_d1mem = if (p.nLsuPort > 1) Some(new Mb4sIO(p.pL0D1Bus)) else None

    val b_wb = Vec(p.nHart, Vec(p.nBackPort, new GenRVIO(p, new LsuAckCtrlBus(p), UInt(p.nDataBit.W))))
  })

  def SADDRMIS: Int = {
    if (p.useField) {
      return CHAMPEXC.SADDRMIS
    } else {
      return PRIVEXC.SADDRMIS
    }
  }

  def LADDRMIS: Int = {
    if (p.useField) {
      return CHAMPEXC.LADDRMIS
    } else {
      return PRIVEXC.LADDRMIS
    }
  }

  val r_flush = RegInit(VecInit(Seq.fill(p.nHart)(false.B)))

  val w_trap = Wire(Vec(p.nBackPort, Bool()))

  // ******************************
  //          HART STATUS
  // ******************************
  val w_hart_valid = Wire(Vec(p.nHart, Bool()))
  val w_hart_flush = Wire(Vec(p.nHart, Bool()))
  val w_hart_field = Wire(Vec(p.nHart, UInt(log2Ceil(p.nField).W)))

  for (h <- 0 until p.nHart) {
    if (p.useField) {
      w_hart_valid(h) := io.b_hart.get(h).valid & ~io.b_hart.get(h).flush
      w_hart_flush(h) := io.b_hart.get(h).flush | io.i_flush(h)
      w_hart_field(h) := io.b_hart.get(h).field
    } else {
      w_hart_valid(h) := true.B
      w_hart_flush(h) := io.i_flush(h)
      w_hart_field(h) := 0.U
    } 
  } 

  // ******************************
  //             REQ
  // ******************************
  val m_req = Seq.fill(p.nHart){Module(new GenFifo(p, new LsuReqCtrlBus(p), UInt(p.nDataBit.W), 4, p.nLsuReqDepth, p.nBackPort, 1))}

  val init_req_hart = Wire(Vec(p.nLsuPort, UInt(log2Ceil(p.nHart).W)))

  init_req_hart(0) := 0.U
  if (p.nHart > 1) {
    init_req_hart(1) := 1.U
  }

  val r_req_hart = RegInit(init_req_hart)

  val w_req_hart = Wire(Vec(p.nLsuPort, UInt(log2Ceil(p.nHart).W)))
  val w_req_field = Wire(Vec(p.nLsuPort, UInt(log2Ceil(p.nField).W)))
  val w_req = Wire(Vec(p.nLsuPort, new GenVBus(p, new LsuReqCtrlBus(p), UInt(p.nDataBit.W))))
  val w_req_lock = Wire(Vec(p.nLsuPort, Bool()))
  val w_req_wait = Wire(Vec(p.nLsuPort, Bool()))
  val w_req_flush = Wire(Vec(p.nLsuPort, Bool()))

  // ------------------------------
  //            BUFFER
  // ------------------------------
  for (h <- 0 until p.nHart) {
    m_req(h).io.i_flush := io.i_flush(h)
    for (bp <- 0 until p.nBackPort) {
      m_req(h).io.b_in(bp) := DontCare
      m_req(h).io.b_in(bp).valid := false.B
    }
  }

  for (bp <- 0 until p.nBackPort) {
    io.b_req(bp).ready := false.B

    // Exception: misaligned address
    w_trap(bp) := false.B
    switch (io.b_req(bp).ctrl.get.size) {
      is (LSUSIZE.H) {
        w_trap(bp) :=     (io.b_req(bp).ctrl.get.addr(0) =/= 0.U)     
      }
      is (LSUSIZE.W) {
        w_trap(bp) :=     (io.b_req(bp).ctrl.get.addr(1, 0) =/= 0.U)    
      }
      is (LSUSIZE.D) {
        if (p.nDataBit >= 64) {
          w_trap(bp) :=   (io.b_req(bp).ctrl.get.addr(2, 0) =/= 0.U)
        }                  
      }
    }
  }

  for (h <- 0 until p.nHart) {
    for (bp <- 0 until p.nBackPort) {
      when (h.U === io.b_req(bp).ctrl.get.hart) {
        io.b_req(bp).ready := m_req(h).io.b_in(bp).ready
        m_req(h).io.b_in(bp).valid := io.b_req(bp).valid
      }

      m_req(h).io.b_in(bp).ctrl.get := io.b_req(bp).ctrl.get
      m_req(h).io.b_in(bp).ctrl.get.hart := h.U
      m_req(h).io.b_in(bp).ctrl.get.trap.valid := w_trap(bp)
      m_req(h).io.b_in(bp).ctrl.get.trap.cause := Mux(io.b_req(bp).ctrl.get.st, SADDRMIS.U, LADDRMIS.U)    
      m_req(h).io.b_in(bp).data.get := io.b_req(bp).data.get
    }
  }

  // ------------------------------
  //            SELECT
  // ------------------------------
  // Hart select
  if (p.nHart > 1) {
    for (lp <- 0 until p.nLsuPort) {
      when (r_req_hart(lp) === (p.nHart - 1).U) {
        r_req_hart(lp) := 0.U
      }.otherwise {
        r_req_hart(lp) := r_req_hart(lp) + 1.U
      }  
    }  
  }
  w_req_hart := r_req_hart
  for (lp <- 0 until p.nLsuPort) {
    w_req_field(lp) := w_hart_field(w_req_hart(lp))
  }

  // Default
  for (lp <- 0 until p.nLsuPort) {
    w_req(lp) := DontCare
    w_req(lp).valid := false.B    
  }

  for (h <- 0 until p.nHart) {
    m_req(h).io.b_out(0).ready := false.B
  }

  // Connect
  for (h <- 0 until p.nHart) {
    when (h.U === w_req_hart(0)) {
      m_req(h).io.b_out(0).ready := ~w_req_lock(0) & ~w_req_wait(0)
      w_req(0).valid := m_req(h).io.b_out(0).valid
      w_req(0).ctrl.get := m_req(h).io.b_out(0).ctrl.get
      w_req(0).ctrl.get.hart := h.U
      w_req(0).data.get := m_req(h).io.b_out(0).data.get
    }

    if (p.nHart > 1) {
      when (h.U === w_req_hart(1)) {
        m_req(h).io.b_out(0).ready := m_req(h).io.b_out(0).ctrl.get.ldo & ~w_req_lock(1) & ~w_req_wait(1)
        w_req(1).valid := m_req(h).io.b_out(0).valid
        w_req(1).ctrl.get := m_req(h).io.b_out(0).ctrl.get
        w_req(1).ctrl.get.hart := h.U
        w_req(1).data.get := m_req(h).io.b_out(0).data.get
      }
    }
  }

  // ------------------------------
  //            PORT 0
  // ------------------------------
  w_req_wait(0) := w_req(0).valid & ~io.b_d0mem.req.ready(w_req_field(0))
  io.b_d0mem.req.valid := w_req(0).valid & ~w_req_lock(0) & ~w_req(0).ctrl.get.trap.valid & ~w_req_flush(0)
  if (p.useField) io.b_d0mem.req.field.get := w_req_field(0)
  io.b_d0mem.req.ctrl.hart := w_req(0).ctrl.get.hart
  io.b_d0mem.req.ctrl.op := w_req(0).ctrl.get.uop
  if (p.useExtA) io.b_d0mem.req.ctrl.amo.get := w_req(0).ctrl.get.amo
  io.b_d0mem.req.ctrl.addr := w_req(0).ctrl.get.addr
  io.b_d0mem.req.ctrl.size := SIZE.B0.U
  switch (w_req(0).ctrl.get.size) {
    is (LSUSIZE.B) {
      io.b_d0mem.req.ctrl.size := SIZE.B1.U
    }
    is (LSUSIZE.H) {
      io.b_d0mem.req.ctrl.size := SIZE.B2.U
    }
    is (LSUSIZE.W) {
      io.b_d0mem.req.ctrl.size := SIZE.B4.U
    }
    is (LSUSIZE.D) {
      if (p.nDataBit >= 64) {
        io.b_d0mem.req.ctrl.size := SIZE.B8.U
      }      
    }
  }

  // ------------------------------
  //            PORT 1
  // ------------------------------
  if (p.nHart > 1) {
    w_req_wait(1) := w_req(1).valid & ~io.b_d1mem.get.req.ready(w_req_field(1))
    io.b_d1mem.get.req.valid := w_req(1).valid & ~w_req_lock(1) & ~w_req(1).ctrl.get.trap.valid & ~w_req_flush(1)
    if (p.useField) io.b_d1mem.get.req.field.get := w_req_field(1)
    io.b_d1mem.get.req.ctrl.hart := w_req(1).ctrl.get.hart
    io.b_d1mem.get.req.ctrl.op := w_req(1).ctrl.get.uop
    if (p.useExtA) io.b_d1mem.get.req.ctrl.amo.get := DontCare
    io.b_d1mem.get.req.ctrl.addr := w_req(1).ctrl.get.addr
    io.b_d1mem.get.req.ctrl.size := SIZE.B1.U
    switch (w_req(1).ctrl.get.size) {
      is (LSUSIZE.B) {
        io.b_d1mem.get.req.ctrl.size := SIZE.B1.U
      }
      is (LSUSIZE.H) {
        io.b_d1mem.get.req.ctrl.size := SIZE.B2.U
      }
      is (LSUSIZE.W) {
        io.b_d1mem.get.req.ctrl.size := SIZE.B4.U
      }
      is (LSUSIZE.D) {
        if (p.nDataBit >= 64) {
          io.b_d1mem.get.req.ctrl.size := SIZE.B8.U
        }      
      }
    }
  }

  // ******************************
  //            MEMORY
  // ******************************
  val m_mem = Seq.fill(p.nHart){Module(new GenFifo(p, new LsuMemCtrlBus(p), UInt(p.nDataBit.W), 4, p.nLsuMemDepth * 2, 1, 1))}

  val m_d0order = Seq.fill(p.nField){Module(new GenFifo(p, UInt(log2Ceil(p.nHart + 1).W), UInt(0.W), 4, p.nLsuMemDepth * p.nHart, 1, 1))}
  val m_d1order = if (p.nHart > 1) Some(Seq.fill(p.nField){Module(new GenFifo(p, UInt(log2Ceil(p.nHart + 1).W), UInt(0.W), 4, p.nLsuMemDepth * p.nHart, 1, 1))}) else None

  for (h <- 0 until p.nHart) {
    m_mem(h).io.i_flush := false.B
    m_mem(h).io.b_in(0) := DontCare
    m_mem(h).io.b_in(0).valid := false.B
  }

  // ------------------------------
  //            PORT 0
  // ------------------------------
  for (d <- 0 until p.nField) {
    m_d0order(d).io.i_flush := false.B
    m_d0order(d).io.b_in(0) := DontCare
    m_d0order(d).io.b_in(0).valid := false.B
  }

  w_req_lock(0) := false.B
  for (h <- 0 until p.nHart) {
    for (d <- 0 until p.nField) {
      when ((h.U === w_req_hart(0)) & (d.U === w_req_field(0))) {
        w_req_lock(0) := ~m_mem(h).io.b_in(0).ready | ~m_d0order(d).io.b_in(0).ready

        m_mem(h).io.b_in(0).valid := w_req(0).valid & ~w_req_wait(0) & ~w_req_flush(0) & m_d0order(d).io.b_in(0).ready
        m_mem(h).io.b_in(0).ctrl.get.hart := w_req(0).ctrl.get.hart
        m_mem(h).io.b_in(0).ctrl.get.uop := w_req(0).ctrl.get.uop
        m_mem(h).io.b_in(0).ctrl.get.amo := w_req(0).ctrl.get.amo
        m_mem(h).io.b_in(0).ctrl.get.port := 0.U
        m_mem(h).io.b_in(0).ctrl.get.size := w_req(0).ctrl.get.size
        m_mem(h).io.b_in(0).ctrl.get.sign := w_req(0).ctrl.get.sign
        m_mem(h).io.b_in(0).ctrl.get.trap := w_req(0).ctrl.get.trap  
        m_mem(h).io.b_in(0).data.get := w_req(0).data.get

        m_d0order(d).io.b_in(0).valid := w_req(0).valid & ~w_req_wait(0) & ~w_req_flush(0) & m_mem(h).io.b_in(0).ready
        m_d0order(d).io.b_in(0).ctrl.get := w_req_hart(0)
      }
    }
  }

  // ------------------------------
  //            PORT 1
  // ------------------------------
  if (p.nHart > 1) {  
    for (d <- 0 until p.nField) {
      m_d1order.get(d).io.i_flush := false.B
      m_d1order.get(d).io.b_in(0) := DontCare
      m_d1order.get(d).io.b_in(0).valid := false.B
    }

    w_req_lock(1) := false.B
    for (h <- 0 until p.nHart) {
      for (d <- 0 until p.nField) {
        when ((h.U === w_req_hart(1)) & (d.U === w_req_field(1))) {
          w_req_lock(1) := ~m_mem(h).io.b_in(0).ready | ~m_d1order.get(d).io.b_in(0).ready

          m_mem(h).io.b_in(0).valid := w_req(1).valid & ~w_req_wait(1) & ~w_req_flush(1) & m_d1order.get(d).io.b_in(0).ready
          m_mem(h).io.b_in(0).ctrl.get.hart := w_req(1).ctrl.get.hart
          m_mem(h).io.b_in(0).ctrl.get.uop := w_req(1).ctrl.get.uop
          m_mem(h).io.b_in(0).ctrl.get.amo := w_req(1).ctrl.get.amo
          m_mem(h).io.b_in(0).ctrl.get.port := 1.U
          m_mem(h).io.b_in(0).ctrl.get.size := w_req(1).ctrl.get.size
          m_mem(h).io.b_in(0).ctrl.get.sign := w_req(1).ctrl.get.sign
          m_mem(h).io.b_in(0).ctrl.get.trap := w_req(1).ctrl.get.trap  
          m_mem(h).io.b_in(0).data.get := w_req(1).data.get

          m_d1order.get(d).io.b_in(0).valid := w_req(1).valid & ~w_req_wait(1) & ~w_req_flush(1) & m_mem(h).io.b_in(0).ready
          m_d1order.get(d).io.b_in(0).ctrl.get := w_req_hart(1)
        }
      }
    }
  }

  // ------------------------------
  //            FLUSH
  // ------------------------------
  for (lp <- 0 until p.nLsuPort) {
    w_req_flush(lp) := false.B

    for (h <- 0 until p.nHart) {
      when (h.U === w_req_hart(lp)) {
        w_req_flush(lp) := r_flush(h)
        
        when (~r_flush(h)) {
          when (w_req(lp).valid & w_req(lp).ctrl.get.trap.valid & ~w_req_lock(lp)) {
            r_flush(h) := true.B
          }
        }.otherwise {
          r_flush(h) := ~w_hart_flush(h)
        }
      }
    }
  }

  // ******************************
  //             ACK
  // ******************************
  val m_rport0 = Module(new Mb4sDataSReg(p.pL0D0Bus))
  val m_rport1 = if (p.nHart > 1) Some(Module(new Mb4sDataSReg(p.pL0D1Bus))) else None
  val r_wd0mem = RegInit(true.B)

  val m_ack = Seq.fill(p.nHart){Module(new GenFifo(p, new LsuAckCtrlBus(p), UInt(p.nDataBit.W), 4, 2, 1, p.nBackPort))}

  val w_ack_hart = Wire(Vec(p.nLsuPort, UInt(log2Ceil(p.nHart).W)))
  val w_ack_field = Wire(Vec(p.nLsuPort, UInt(log2Ceil(p.nField).W)))
  val w_ack = Wire(Vec(p.nLsuPort, new GenVBus(p, new LsuMemCtrlBus(p), UInt(p.nDataBit.W))))
  val w_ack_read = Wire(Vec(p.nLsuPort, UInt(p.nDataBit.W)))
  val w_ack_lock = Wire(Vec(p.nLsuPort, Bool()))
  val w_ack_wait = Wire(Vec(p.nLsuPort, Bool()))

  // ------------------------------
  //            SELECT
  // ------------------------------
  for (h <- 0 until p.nHart) {
    m_mem(h).io.b_out(0).ready := false.B
  }

  for (d <- 0 until p.nField) {
    m_d0order(d).io.b_out(0).ready := false.B
    if (p.nHart > 1) m_d1order.get(d).io.b_out(0).ready := false.B
  }

  w_ack_hart := w_req_hart

  w_ack := DontCare
  for (lp <- 0 until p.nLsuPort) {
    w_ack(lp).valid := false.B
    w_ack_field(lp) := w_hart_field(w_ack_hart(lp))
  }

  for (h <- 0 until p.nHart) {
    for (d <- 0 until p.nField) {
      for (lp <- 0 until p.nLsuPort) {
        when ((h.U === w_ack_hart(lp)) & (d.U === w_ack_field(lp))) {
          m_mem(h).io.b_out(0).ready := ~w_ack_wait(lp) & ~w_ack_lock(lp) & (m_mem(h).io.b_out(0).ctrl.get.port === lp.U)
          if (lp == 1) {
            m_d1order.get(d).io.b_out(0).ready := ~w_ack_wait(lp) & ~w_ack_lock(lp) & (m_mem(h).io.b_out(0).ctrl.get.port === lp.U)
          } else {
            m_d0order(d).io.b_out(0).ready := ~w_ack_wait(lp) & ~w_ack_lock(lp) & (m_mem(h).io.b_out(0).ctrl.get.port === lp.U)
          }          

          w_ack(lp).valid := m_mem(h).io.b_out(0).valid & (m_mem(h).io.b_out(0).ctrl.get.port === lp.U)
          w_ack(lp).ctrl.get := m_mem(h).io.b_out(0).ctrl.get
          w_ack(lp).data.get := m_mem(h).io.b_out(0).data.get
        }
      }
    }
  }

  // ------------------------------
  //            PORT 0
  // ------------------------------
  if (p.pL0D0Bus.useFieldSlct) m_rport0.io.i_slct.get := w_ack_field(0)
  m_rport0.io.b_port <> io.b_d0mem.read

  if (p.useExtA) {
    w_ack_wait(0) := ~w_ack(0).ctrl.get.trap.valid & ((w_ack(0).ctrl.get.ld & ~m_rport0.io.b_sout.valid) | (w_ack(0).ctrl.get.st & ~io.b_d0mem.write.ready(w_ack_field(0)) & r_wd0mem))
  } else {
    w_ack_wait(0) := ~w_ack(0).ctrl.get.trap.valid & ((w_ack(0).ctrl.get.ld & ~m_rport0.io.b_sout.valid) | (w_ack(0).ctrl.get.st & ~io.b_d0mem.write.ready(w_ack_field(0))))
  }  

  m_rport0.io.b_sout.ready := w_ack(0).valid & w_ack(0).ctrl.get.ld & ~w_ack(0).ctrl.get.trap.valid & ~w_ack_lock(0)
  w_ack_read(0) := m_rport0.io.b_sout.data.get

  io.b_d0mem.write.valid := w_ack(0).valid & w_ack(0).ctrl.get.st & r_wd0mem & ~w_ack(0).ctrl.get.trap.valid & ~w_ack_lock(0)
  if (p.useField) io.b_d0mem.write.field.get := w_ack_field(0)
  io.b_d0mem.write.data := w_ack(0).data.get  

  if (p.useExtA) {
    when (w_ack(0).valid & w_ack(0).ctrl.get.a) {      
      when (r_wd0mem) {
        r_wd0mem := (m_rport0.io.b_sout.valid & ~w_ack_lock(0)) | ~io.b_d0mem.write.ready(0)
      }.otherwise {
        r_wd0mem := (m_rport0.io.b_sout.valid & ~w_ack_lock(0))
      }
    }
  }

  // ------------------------------
  //            PORT 1
  // ------------------------------
  if (p.nLsuPort > 1) {
    if (p.pL0D1Bus.useFieldSlct) m_rport1.get.io.i_slct.get := w_ack_field(1)
    m_rport1.get.io.b_port <> io.b_d1mem.get.read

    w_ack_wait(1) := ~w_ack(1).ctrl.get.trap.valid & ~m_rport1.get.io.b_sout.valid

    m_rport1.get.io.b_sout.ready := w_ack(1).valid & ~w_ack(1).ctrl.get.trap.valid & ~w_ack_lock(1)
    w_ack_read(1) := m_rport1.get.io.b_sout.data.get

    io.b_d1mem.get.write := DontCare
    io.b_d1mem.get.write.valid := false.B
  }

  // ------------------------------
  //            BUFFER
  // ------------------------------
  for (h <- 0 until p.nHart) {
    m_ack(h).io.i_flush := io.i_flush(h)
    m_ack(h).io.b_in(0) := DontCare
    m_ack(h).io.b_in(0).valid := false.B
  }

  for (lp <- 0 until p.nLsuPort) {
    w_ack_lock(lp) := false.B
  }

  for (h <- 0 until p.nHart) {
    for (lp <- 0 until p.nLsuPort) {
      when (h.U === w_ack_hart(lp)) {
        w_ack_lock(lp) := ~m_ack(h).io.b_in(0).ready

        m_ack(h).io.b_in(0).valid := w_ack(lp).valid & ~w_ack_wait(lp)
        m_ack(h).io.b_in(0).ctrl.get.trap := w_ack(lp).ctrl.get.trap
        
        m_ack(h).io.b_in(0).data.get := w_ack_read(lp)
        switch (w_ack(lp).ctrl.get.size) {
          is (LSUSIZE.B) {
            when (w_ack(lp).ctrl.get.sign === LSUSIGN.S) {
              m_ack(h).io.b_in(0).data.get :=     Cat(Fill(p.nDataBit - 8,    w_ack_read(lp)(7)),     w_ack_read(lp)(7,   0))
            }.otherwise {  
              m_ack(h).io.b_in(0).data.get :=     Cat(Fill(p.nDataBit - 8,    0.B),                   w_ack_read(lp)(7,   0))
            }
          }
          is (LSUSIZE.H) {
            when (w_ack(lp).ctrl.get.sign === LSUSIGN.S) {
              m_ack(h).io.b_in(0).data.get :=     Cat(Fill(p.nDataBit - 16,   w_ack_read(lp)(15)),    w_ack_read(lp)(15,  0))
            }.otherwise {  
              m_ack(h).io.b_in(0).data.get :=     Cat(Fill(p.nDataBit - 16,   0.B),                   w_ack_read(lp)(15,  0))
            }
          }
          is (LSUSIZE.W) {
            if (p.nDataBit < 64) {
              m_ack(h).io.b_in(0).data.get :=     w_ack_read(lp)(31,  0)
            } else {
              m_ack(h).io.b_in(0).data.get :=     Cat(Fill(p.nDataBit - 32,   0.B),                   w_ack_read(lp)(31,  0))
              when (w_ack(lp).ctrl.get.sign === LSUSIGN.S) {
                m_ack(h).io.b_in(0).data.get :=   Cat(Fill(p.nDataBit - 32,   w_ack_read(lp)(31)),    w_ack_read(lp)(31,  0))
              }
            }
          }
          is (LSUSIZE.D) {
            if (p.nDataBit == 64) {
              m_ack(h).io.b_in(lp).data.get :=    w_ack_read(lp)(63,  0)
            }           
          }
        }
      }
    }
  }

  for (h <- 0 until p.nHart) {
    for (bp <- 0 until p.nBackPort) {
      m_ack(h).io.b_out(bp).ready := io.b_wb(h)(bp).ready
      io.b_wb(h)(bp).valid := m_ack(h).io.b_out(bp).valid
      io.b_wb(h)(bp).ctrl.get := m_ack(h).io.b_out(bp).ctrl.get
      io.b_wb(h)(bp).data.get := m_ack(h).io.b_out(bp).data.get
    }
  }

  // ******************************
  //             TRAP
  // ******************************
  for (h <- 0 until p.nHart) {
    io.o_stop(h) := false.B 
    for (bp <- 0 until p.nBackPort) {
      when (io.b_req(bp).valid & (h.U === io.b_req(bp).ctrl.get.hart) & m_req(h).io.b_in(bp).ready & w_trap(bp)) {
        io.o_stop(h) := true.B
      }
    }
  }

  // ******************************
  //             FIELD
  // ******************************
  if (p.useField) {
    for (h <- 0 until p.nHart) {
      io.b_hart.get(h).free := ~m_req(h).io.o_val(0).valid & ~m_mem(h).io.o_val(0).valid & ~m_ack(h).io.o_val(0).valid
    } 
  }

  // ******************************
  //             DEBUG
  // ******************************
  if (p.debug) {
    
  }
}

object Lsu extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new Lsu(BackConfigBase), args)
}
