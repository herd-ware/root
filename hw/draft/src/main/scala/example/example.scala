/*
 * File: example.scala                                                         *
 * Created Date: 2023-02-25 01:16:16 pm                                        *
 * Author: Mathieu Escouteloup                                                 *
 * -----                                                                       *
 * Last Modified: 2023-02-25 09:39:48 pm                                       *
 * Modified By: Mathieu Escouteloup                                            *
 * -----                                                                       *
 * License: See LICENSE.md                                                     *
 * Copyright (c) 2023 HerdWare                                                *
 * -----                                                                       *
 * Description:                                                                *
 */


package herd.draft.example

import chisel3._
import chisel3.util._
// import circt.stage.ChiselStage

class Example (nBit: Int) extends Module {
  val io = IO(new Bundle {
    val i_s1 = Input(UInt(nBit.W))    
    val i_s2 = Input(UInt(32.W))  
    val o_res = Output(UInt(nBit.W))  
  })  

  val r_reg = Reg(UInt(nBit.W))
  
  r_reg := io.i_s1 + io.i_s2

  io.o_res := r_reg
}

object Example extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new Example(4), args)
}

// object Example2 extends App {
//   ChiselStage.emitSystemVerilogFile(new Example(4), args)
// }
