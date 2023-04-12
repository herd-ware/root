/*
 * File: configs.scala                                                         *
 * Created Date: 2023-03-08 01:51:25 pm                                        *
 * Author: Mathieu Escouteloup                                                 *
 * -----                                                                       *
 * Last Modified: 2023-04-12 09:51:18 am                                       *
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

import herd.core.aubrac.nlp._
import herd.core.salers.back._


// ******************************
//       PIPELINE PARAMETERS 
// ******************************
object PipelineConfigBase extends PipelineConfig (
  // ------------------------------
  //            GLOBAL
  // ------------------------------
  debug = true,
  pcBoot = "00001000",
  nAddrBit = 32,
  nDataBit = 32,

  // ------------------------------
  //             CHAMP
  // ------------------------------
  useChamp = false,
  nField = 1,
  nPart = 1,
  nChampTrapLvl = 1,

  // ------------------------------
  //           FRONT END
  // ------------------------------
  nFetchInstr = 2,
  useIMemSeq = true,
  useIf1Stage = false,
  useIf2Stage = false,
  nFetchBufferDepth = 8,  
  useFastJal = false,

  // ------------------------------
  //       NEXT-LINE PREDICTOR
  // ------------------------------
  useNlp = true,
  nBtbLine = 8,
  nBhtSet = 8,
  nBhtSetEntry = 128,
  nBhtBit = 2,
  useRsbSpec = true,
  nRsbDepth = 8,

  // ------------------------------
  //           BACK END
  // ------------------------------
  useExtA = false,
  useExtZifencei = true,
  useExtZicbo = true,
  nBackPort = 2,
  nExStage = 2, 
  nAlu = 2,
  nMulDiv = 1,
  isBAlu = Array(false, false),
  isClMul = Array(false),
  useBranchReg = false,
  nLsuMemDepth = 2,
  nGprReadPhy = 4,
  nGprWritePhy = 2
)

// ******************************
//       GAVARNIE PARAMETERS 
// ******************************
object SalersConfigBase extends SalersConfig (
  // ------------------------------
  //            GLOBAL
  // ------------------------------
  debug = true,
  pcBoot = "00001000",
  nAddrBit = 32,
  nDataBit = 32,

  // ------------------------------
  //             CHAMP
  // ------------------------------
  useChamp = false,
  nChampReg = 4,
  useChampExtMie = true,
  useChampExtFr = false,
  useChampExtCst = false,
  nChampTrapLvl = 2,

  nPart = 2,
  nFieldFlushCycle = 20,

  // ------------------------------
  //           FRONT END
  // ------------------------------
  nFetchInstr = 2,
  useIMemSeq = true,
  useIf1Stage = false,
  useIf2Stage = false,
  nFetchBufferDepth = 8,  
  useFastJal = false,

  // ------------------------------
  //       NEXT-LINE PREDICTOR
  // ------------------------------
  useNlp = true,
  nBtbLine = 8,
  nBhtSet = 8,
  nBhtSetEntry = 128,
  nBhtBit = 2,
  useRsbSpec = true,
  nRsbDepth = 8,

  // ------------------------------
  //           BACK END
  // ------------------------------
  useExtA = false,
  useExtZifencei = true,
  useExtZicbo = true,
  nBackPort = 2,
  nExStage = 2, 
  nAlu = 1,
  nMulDiv = 1,
  isBAlu = Array(false, false),
  isClMul = Array(false),
  useBranchReg = false,
  nLsuMemDepth = 2,
  nGprReadPhy = 4,
  nGprWritePhy = 2,

  // ------------------------------
  //              I/Os
  // ------------------------------
  nIOAddrBase = "00100000",
  nScratch = 2,
  nCTimer = 2,
  isHpmAct = Array("ALL"),
  hasHpmMap = Array(),

  nUnCacheBase = "70000000",
  nUnCacheByte = "01000000",

  // ------------------------------
  //           L1I CACHE
  // ------------------------------
  useL1I = true,
  nL1INextDataByte = 8,
  nL1INextLatency = 1,

  useL1IPftch = false,
  nL1IPftchEntry = 4,
  nL1IPftchEntryAcc = 1,
  nL1IPftchMemRead = 1,
  nL1IPftchMemWrite = 1,

  nL1IMem = 1,
  nL1IMemReadPort = 2,
  nL1IMemWritePort = 1,

  slctL1IPolicy = "BitPLRU",
  nL1ISet = 4,
  nL1ILine = 4,
  nL1IData = 4,

  // ------------------------------
  //           L1D CACHE
  // ------------------------------
  useL1D = true,
  nL1DNextDataByte = 8,
  nL1DNextLatency = 1,

  useL1DPftch = false,
  nL1DPftchEntry = 4,
  nL1DPftchEntryAcc = 1,
  nL1DPftchMemRead = 1,
  nL1DPftchMemWrite = 1,

  nL1DMem = 1,
  nL1DMemReadPort = 2,
  nL1DMemWritePort = 1,

  slctL1DPolicy = "BitPLRU",
  nL1DSet = 4,
  nL1DLine = 4,
  nL1DData = 4,

  // ------------------------------
  //           L2 CACHE
  // ------------------------------
  useL2 = true,
  nL2NextDataByte = 8,
  useL2ReqReg = true,
  useL2AccReg = false,
  useL2AckReg = false,
  nL2WriteFifoDepth = 2,
  nL2NextFifoDepth = 2,
  nL2NextLatency = 1,

  useL2Pftch = false,
  nL2PftchEntry = 4,
  nL2PftchEntryAcc = 1,
  nL2PftchMemRead = 1,
  nL2PftchMemWrite = 1,

  nL2Mem = 2,
  nL2MemReadPort = 2,
  nL2MemWritePort = 1,

  slctL2Policy = "BitPLRU",
  nL2Set = 4,
  nL2Line = 4,
  nL2Data = 4
)
