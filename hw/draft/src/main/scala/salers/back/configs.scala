/*
 * File: configs.scala                                                         *
 * Created Date: 2023-03-08 01:51:25 pm                                        *
 * Author: Mathieu Escouteloup                                                 *
 * -----                                                                       *
 * Last Modified: 2023-04-12 09:15:28 am                                       *
 * Modified By: Mathieu Escouteloup                                            *
 * -----                                                                       *
 * License: See LICENSE.md                                                     *
 * Copyright (c) 2023 HerdWare                                                 *
 * -----                                                                       *
 * Description:                                                                *
 */


package herd.core.salers.back

import chisel3._


object DispatcherConfigBase extends DispatcherConfig (
  debug = true,
  nHart = 2,

  nBackPort = 2,

  nAlu = 2,
  nMulDiv = 1,
  nBAlu = 0,
  nClMul = 0
) 

object BackConfigBase extends BackConfig (
  debug = true,
  pcBoot = "40",
  nAddrBit = 32,
  nDataBit = 32,
  nHart = 1,
  
  useChamp = true,
  nField = 1,
  nPart = 1,
  nChampTrapLvl = 1,

  nBackPort = 2,
  nExStage = 1,
  nAlu = 2,
  nMulDiv = 1,
  isBAlu = Array(false, false),
  isClMul = Array(false),
  useExtA = false,
  useExtZifencei = true,
  useExtZicbo = true,
  useBranchReg = true,
  nLsuMemDepth = 2,

  nGprReadPhy = 4,
  nGprWritePhy = 2
)