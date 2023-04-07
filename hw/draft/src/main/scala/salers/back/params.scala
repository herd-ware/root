/*
 * File: params.scala                                                          *
 * Created Date: 2023-03-08 01:51:25 pm                                        *
 * Author: Mathieu Escouteloup                                                 *
 * -----                                                                       *
 * Last Modified: 2023-03-09 12:10:39 pm                                       *
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

import herd.common.mem.mb4s._
import herd.core.aubrac.back.{GprParams,DecoderParams}
import herd.core.aubrac.back.csr.{CsrParams}


trait DispatcherParams {
  def debug: Boolean
  def nHart: Int

  def nBackPort: Int

  def nAlu: Int
  def nBru: Int = nHart
  def nMulDiv: Int
}

case class DispatcherConfig (
  debug: Boolean,
  nHart: Int,

  nBackPort: Int,

  nAlu: Int,
  nMulDiv: Int
) extends DispatcherParams

trait BackParams extends GprParams 
                  with DecoderParams
                  with CsrParams
                  with DispatcherParams {
  def debug: Boolean
  def pcBoot: String
  def nHart: Int
  def nAddrBit: Int
  def nDataBit: Int
  def nDataByte: Int = (nDataBit / 8).toInt

  def useChamp: Boolean
  def nField: Int
  def nPart: Int
  def nChampTrapLvl: Int

  def nBackPort: Int
  def nExStage: Int 
  def nAlu: Int
  def nMulDiv: Int
  def useExtM: Boolean = (nMulDiv > 0)
  def useExtA: Boolean
  def useExtB: Boolean
  def useExtZifencei: Boolean
  def useExtZicbo: Boolean
  def useCbo: Boolean = useExtZifencei || useExtZicbo
  def useBranchReg: Boolean
  def nCommit: Int = nBackPort
  
  def nLsuPort: Int = {
    if (nHart > 1) {
      return 2
    } else {
      return 1
    }
  }
  def nLsuReqDepth: Int = nBackPort * nExStage
  def nLsuMemDepth: Int
  
  def nGprReadPhy: Int
  def nGprWritePhy: Int
  def nGprReadLog: Int = 2 * nBackPort
  def nGprWriteLog: Int = nBackPort
  def nGprBypass: Int = nBackPort * (nExStage + 2)

  def pL0D0Bus: Mb4sParams = new Mb4sConfig (
    debug = debug,
    readOnly = false,
    nHart = nHart,
    nAddrBit = nAddrBit,
    useAmo = useExtA,
    nDataByte = nDataByte,
    
    useField = useChamp,
    nField = nField,
    multiField = (nHart > 1)
  )

  def pL0D1Bus: Mb4sParams = new Mb4sConfig (
    debug = debug,
    readOnly = true,
    nHart = nHart,
    nAddrBit = nAddrBit,
    useAmo = useExtA,
    nDataByte = nDataByte,
    
    useField = useChamp,
    nField = nField,
    multiField = (nHart > 1)
  )
}

case class BackConfig (
  debug: Boolean,
  pcBoot: String,
  nHart: Int,
  nAddrBit: Int,
  nDataBit: Int,
  
  useChamp: Boolean,
  nField: Int,
  nPart: Int,
  nChampTrapLvl: Int,

  nBackPort: Int,
  nExStage: Int,
  nAlu: Int,
  nMulDiv: Int,
  useExtA: Boolean,
  useExtB: Boolean,
  useExtZifencei: Boolean,
  useExtZicbo: Boolean,
  useBranchReg: Boolean,
  nLsuMemDepth: Int,

  nGprReadPhy: Int,
  nGprWritePhy: Int
) extends BackParams
