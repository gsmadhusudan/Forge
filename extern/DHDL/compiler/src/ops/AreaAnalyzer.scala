package dhdl.compiler.ops

import scala.reflect.{Manifest,SourceContext}
import scala.virtualization.lms.internal.Traversal

import dhdl.shared._
import dhdl.shared.ops._
import dhdl.compiler._
import dhdl.compiler.ops._

trait AreaAnalysisExp extends AreaModel with LatencyModel with CounterToolsExp with PipeStageToolsExp {
  this: DHDLExp =>

  // TODO: This shouldn't be hardcoded (or here at all really)
  val BaseDesign = FPGAResources(
    lut7 = 468,
    lut6 = 9200,
    lut5 = 12350,
    lut4 = 11140,
    lut3 = 22600,
    mem64 = 4619,
    mem32 = 519,
    mem16 = 559,
    regs = 75400,
    dsps = 0,
    bram = 338
  )

  val FPGATarget = FPGAResourceSummary(alms=262400,regs=0,dsps=1963,bram=2567)  // Stratix V on MAIA board
}

trait AreaAnalyzer extends ModelingTools {
  val IR: AreaAnalysisExp with DHDLExp
  import IR._
  import ReductionTreeAnalysis._

  override def silenceTraversal() {
    super.silenceTraversal()
    IR.silenceAreaModel()
  }

  var totalArea = FPGAResourceSummary()
  var areaScope: List[FPGAResources] = Nil

  def areaOf(e: Exp[Any]) = IR.areaOf(e, inReduce, inHwScope)

  def areaOfBlock(b: Block[Any], innerLoop: Boolean): FPGAResources = {
    val outerScope = areaScope
    areaScope = Nil
    traverseBlock(b)
    val area = areaScope.fold(NoArea){_+_}
    areaScope = outerScope

    if (innerLoop) {
      val delays = pipeDelays(b)
      val delayLineArea = delays.map {
        case (sym,len) if isBits(sym.tp) => areaOfDelayLine(nbits(sym.tp), len)
        case _ => NoArea
      }
      area + delayLineArea.fold(NoArea){_+_}
    }
    else
      area
  }
  def areaOfCycle(b: Block[Any]): FPGAResources = {
    val outerReduce = inReduce
    inReduce = true
    val out = areaOfBlock(b, true)
    inReduce = outerReduce
    out
  }

  // TODO: loop index delay line in Metapipeline
  def traverseNode(lhs: Exp[Any], rhs: Def[Any])(implicit ctx: SourceContext) {
    val area = rhs match {
      case EatReflect(Hwblock(blk)) =>
        inHwScope = true
        val body = areaOfBlock(blk, false)
        inHwScope = false
        body

      case EatReflect(Counterchain_new(ctrs,nIters)) => areaOfBlock(nIters,true) + areaOf(lhs)
      case EatReflect(Pipe_parallel(func)) => areaOfBlock(func,false) + areaOf(lhs)
      case EatReflect(Unit_pipe(func)) =>
        areaOfBlock(func, styleOf(lhs) == Fine) + areaOf(lhs)

      case EatReflect(Pipe_foreach(cchain, func, _)) =>
        val P = parOf(cchain).reduce(_*_)
        areaOfBlock(func, styleOf(lhs) == Fine) * P + areaOf(lhs)

      case EatReflect(e@Pipe_reduce(cchain,_,ld,st,func,rFunc,_,_,_,_)) =>
        val P = parOf(cchain).reduce(_*_)
        val body = areaOfBlock(func, styleOf(lhs) == Fine) * P // map duplicated P times
        /*
          Some simple math:
          A full binary (reduction) tree is a tree in which every node is either
          a leaf or has exactly two children.
          The number of internal (non-leaf) nodes of a full tree with L leaves is L - 1
          In our case, L is the map's parallelization factor P
          and internal nodes represent duplicates of the reduction function
          The reduction function is therefore duplicated P - 1 times
          Plus the special, tightly cyclic reduction function to update the accumulator
        */
        val internal = areaOfBlock(rFunc, true) * (P - 1)
        val rFuncLatency = latencyOfPipe(rFunc)
        val internalDelays = reductionTreeDelays(P).map{delay => areaOfDelayLine(nbits(e.mT), rFuncLatency * delay) }.fold(NoArea){_+_}
        val load  = areaOfCycle(ld)    // Load from accumulator (happens once)
        val cycle = areaOfCycle(rFunc) // Special case area of accumulator
        val store = areaOfCycle(st)    // Store to accumulator (happens once)

        body + internal + internalDelays + load + cycle + store + areaOf(lhs)

      case EatReflect(e@Block_reduce(ccOuter,ccInner,_,func,ld1,ld2,rFunc,st,_,_,_,_,_,_)) =>
        val Pm = parOf(ccOuter).reduce(_*_) // Parallelization factor for map
        val Pr = parOf(ccInner).reduce(_*_) // Parallelization factor for reduce
        val body = areaOfBlock(func,false) * Pm

        val internal = areaOfBlock(rFunc,true) * (Pm - 1) * Pr
        val rFuncLatency = latencyOfPipe(rFunc)
        val internalDelays = reductionTreeDelays(Pm).map{delay => areaOfDelayLine(nbits(e.mT), rFuncLatency * delay) * Pr}.fold(NoArea){_+_}
        val load1 = areaOfBlock(ld1,true) * Pm * Pr
        val load2 = areaOfCycle(ld2) * Pr
        val cycle = areaOfCycle(rFunc) * Pr
        val store = areaOfCycle(st) * Pr

        body + internal + internalDelays + load1 + load2 + cycle + store + areaOf(lhs)

      case _ =>
        blocks(rhs).map(blk => areaOfBlock(blk,false)).fold(NoArea){_+_} + areaOf(lhs)
    }
    areaScope ::= area
  }

  override def preprocess[A:Manifest](b: Block[A]) = {
    areaScope = Nil
    super.preprocess(b)
  }

  override def postprocess[A:Manifest](b: Block[A]) = {
    // TODO: Could potentially have multiple accelerator designs in a single program
    // Eventually want to be able to support multiple accel scopes
    val design = areaScope.fold(NoArea){_+_} + IR.BaseDesign

    val routingLUTs = 21000 // lutRoutingModel.evaluate(design)
    val fanoutRegs  = 3600  // regFanoutModel.evaluate(design)
    val unavailable = 400   // unavailALMsModel.evaluate(design)

    val recoverable = design.lut3/2 + design.lut4/2 + design.lut5/2 + design.lut6/10 + design.mem16/2  + routingLUTs/2

    val logicALMs = design.lut3 + design.lut4 + design.lut5 + design.lut6 + design.lut7 +
                    design.mem16 + design.mem32 + design.mem64 + routingLUTs - recoverable + unavailable
    val totalRegs = design.regs + fanoutRegs

    val regALMs = Math.max( ((totalRegs - (logicALMs*2.16))/3).toInt, 0)

    val totalALMs = logicALMs + regALMs

    val dupBRAMs = Math.max(0.02*routingLUTs - 500, 0.0).toInt

    val totalDSPs = design.dsps
    val totalBRAM = design.bram + dupBRAMs

    msg(s"Resource Estimate Breakdown: ")
    msg(s"-----------------------------")
    msg(s"Estimated unavailable ALMs: $unavailable")
    msg(s"LUT3: ${design.lut3}")
    msg(s"LUT4: ${design.lut4}")
    msg(s"LUT5: ${design.lut5}")
    msg(s"LUT6: ${design.lut6}")
    msg(s"LUT7: ${design.lut7}")
    msg(s"Estimated routing luts: $routingLUTs")
    msg(s"Logic+register ALMs: $logicALMs")
    msg(s"Register-only ALMS:  $regALMs")
    msg(s"Recovered ALMs:      $recoverable")
    msg(s"MEM64: ${design.mem64}")
    msg(s"MEM32: ${design.mem32}")
    msg(s"MEM16: ${design.mem16}")
    msg(s"Design registers: ${design.regs}")
    msg(s"Fanout registers: $fanoutRegs")
    msg(s"Design BRAMs: ${design.bram}")
    msg(s"Fanout BRAMs: $dupBRAMs")
    msg("")
    msg(s"Resource Estimate Summary: ")
    msg(s"---------------------------")
    msg(s"ALMs: $totalALMs / ${FPGATarget.alms} (" + "%.2f".format(100*totalALMs.toDouble/FPGATarget.alms) + "%)")
    msg(s"Regs: $totalRegs")
    msg(s"DSPs: $totalDSPs / ${FPGATarget.dsps} (" + "%.2f".format(100*totalDSPs.toDouble/FPGATarget.dsps) + "%)")
    msg(s"BRAM: $totalBRAM / ${FPGATarget.bram} (" + "%.2f".format(100*totalBRAM.toDouble/FPGATarget.bram) + "%)")
    msg("")

    totalArea = FPGAResourceSummary(totalALMs, totalRegs, totalDSPs, totalBRAM)
    (b)
  }

}