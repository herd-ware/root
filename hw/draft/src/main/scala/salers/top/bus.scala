/*
 * File: bus.scala                                                             *
 * Created Date: 2023-03-08 01:51:25 pm                                        *
 * Author: Mathieu Escouteloup                                                 *
 * -----                                                                       *
 * Last Modified: 2023-04-12 09:16:18 am                                       *
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

import herd.common.core.{HpcBus}
import herd.core.aubrac.back.csr.{CsrBus}


// ******************************
//             DEBUG
// ******************************
class PipelineDbgBus (p: PipelineParams) extends Bundle {
  val last = UInt(p.nAddrBit.W)
  val x = Vec(32, UInt(p.nDataBit.W))
  val csr = new CsrBus(p.nDataBit, p.useChamp)
}

class SalersDbgBus (p: SalersParams) extends Bundle {
  val last = UInt(p.nAddrBit.W)
  val x = Vec(32, UInt(p.nDataBit.W))
  val csr = new CsrBus(p.nDataBit, p.useChamp)
  val hf = if (p.useChamp) Some(Vec(p.nChampReg, Vec(6, UInt(p.nDataBit.W)))) else None
  val hpc = new HpcBus()
}          