package dhdl.compiler.ops

import scala.virtualization.lms.common.{EffectExp, ScalaGenEffect, DotGenEffect, MaxJGenEffect}
import scala.virtualization.lms.internal.{Traversal}
import scala.reflect.{Manifest,SourceContext}
import scala.collection.mutable.Set
import java.io.{File, FileWriter, PrintWriter}
import ppl.delite.framework.transform.{DeliteTransform}

import dhdl.shared._
import dhdl.shared.ops._
import dhdl.compiler._
import dhdl.compiler.ops._

trait CounterExternOpsExp extends CounterOpsExp {
  this: DHDLExp =>

  // --- Nodes
  case class Counter_new(start: Rep[Idx], end: Rep[Idx], step: Rep[Idx], par: Param[Int])(implicit val ctx: SourceContext) extends Def[Counter]
  case class Counterchain_new(counters: List[Rep[Counter]], nIter: Block[Idx])(implicit val ctx: SourceContext) extends Def[CounterChain]

  // --- Internal API
  def counter_new(start: Rep[Idx],end: Rep[Idx],step: Rep[Idx], par: Rep[Int])(implicit ctx: SourceContext) = {
    val truePar: Param[Int] = par match {
      case Const(c) =>
        val p = param(c)
        domainOf(p) = (c,c,1)
        p
      case p: Param[_] => p.asInstanceOf[Param[Int]]

      case _ => stageError("Counter parallelization factor must be a parameter or a constant")
    }
    reflectEffect[Counter](Counter_new(start,end,step,truePar)(ctx))
  }

  private def counterSplit(x: Rep[Counter]): (Rep[Idx],Rep[Idx],Rep[Idx],Param[Int]) = x match {
    case Def(EatReflect(Counter_new(start,end,step,par))) => (start,end,step,par)
    case _ => throw new Exception("Could not find def for counter")
  }

  def counterchain_new(counters: List[Rep[Counter]])(implicit ctx: SourceContext): Rep[CounterChain] = {
    val ctrSplit = counters.map{ctr => counterSplit(ctr)}
    val starts: List[Rep[Idx]] = ctrSplit.map(_._1)
    val ends:   List[Rep[Idx]] = ctrSplit.map(_._2)
    val steps:  List[Rep[Idx]] = ctrSplit.map(_._3)
    val pars:   List[Rep[Idx]] = ctrSplit.map(_._4).map(int_to_fix[Signed,B32](_)) // HACK: Convert Int param to fixed point

    val nIter: Block[Idx] = reifyEffects {
      val lens: List[Rep[Idx]] = starts.zip(ends).map{
        case (ConstFix(x: Int),ConstFix(y: Int)) => (y - x).as[Idx]
        case (ConstFix(0), end) => end
        case (start, end) => sub_fix(end, start)
      }
      val total: List[Rep[Idx]] = (lens,steps,pars).zipped.map {
        case (len, ConstFix(1), par) => div_fix(len, par) // Should round correctly here
        case (len, step, par) => div_fix(len, mul_fix(step, par))
      }
      total.reduce{_*_}
    }

    // HACK: Not actually mutable, but isn't being scheduled properly otherwise
    reflectMutable(Counterchain_new(counters, nIter)(ctx))
  }

  // --- Analysis tools
  def parOf(cc: Rep[CounterChain]): List[Int] = parParamsOf(cc).map(_.x)

  def parParamsOf(cc: Rep[CounterChain]): List[Param[Int]] = cc match {
    case Def(EatReflect(Counterchain_new(ctrs,nIter))) => ctrs.map{
      case Def(EatReflect(Counter_new(_,_,_,par))) => par
    }
  }

  def offsets(cc: Rep[CounterChain]) = cc match {
    case Def(EatReflect(Counterchain_new(ctrs,nIter))) => ctrs.map{
      case Def(EatReflect(Counter_new(start,_,_,par))) => start
    }
  }

  def isUnitCounterChain(e: Exp[Any]): Boolean = e match {
    case Def(EatReflect(Counterchain_new(ctrs,_))) if ctrs.length == 1 => isUnitCounter(ctrs(0))
    case _ => false
  }

  def isUnitCounter(e: Exp[Any]): Boolean = e match {
    case Def(EatReflect(Counter_new(ConstFix(0),ConstFix(1),ConstFix(1),_))) => true
    case _ => false
  }

  // TODO: Default number of iterations if bound can't be computed?
  def nIters(x: Rep[CounterChain]): Long = x match {
    case Def(EatReflect(Counterchain_new(_,nIters))) => Math.ceil( bound(nIters.res).getOrElse(1.0) ).toLong
    case _ => 1L
  }


  // --- Mirroring
  override def mirror[A:Manifest](e: Def[A], f: Transformer)(implicit pos: SourceContext): Exp[A] = e match {
    case e@Counter_new(s,end,t,p) => reflectPure( Counter_new(f(s),f(end),f(t),p)(pos) )(mtype(manifest[A]), pos)
    case Reflect(e@Counter_new(s,end,t,p), u, es) => reflectMirrored(Reflect(Counter_new(f(s),f(end),f(t),p)(e.ctx), mapOver(f,u), f(es)))(mtype(manifest[A]), pos)

    case e@Counterchain_new(counters,nIter) => reflectPure(Counterchain_new(f(counters),f(nIter))(e.ctx))(mtype(manifest[A]), pos)
    case Reflect(e@Counterchain_new(counters,nIter), u, es) => reflectMirrored(Reflect(Counterchain_new(f(counters),f(nIter))(e.ctx), mapOver(f,u), f(es)))(mtype(manifest[A]), pos)

    case _ => super.mirror(e,f)
  }

  // --- Syms
  override def syms(e: Any): List[Sym[Any]] = e match {
    case Counterchain_new(ctrs, nIters) => syms(ctrs) ::: syms(nIters)
    case _ => super.syms(e)
  }
  override def readSyms(e: Any): List[Sym[Any]] = e match {
    case Counterchain_new(ctrs, nIters) => readSyms(ctrs) ::: readSyms(nIters)
    case _ => super.readSyms(e)
  }
  override def symsFreq(e: Any): List[(Sym[Any], Double)] = e match {
    case Counterchain_new(ctrs, nIters) => freqNormal(ctrs) ::: freqNormal(nIters)
    case _ => super.symsFreq(e)
  }
  override def boundSyms(e: Any): List[Sym[Any]] = e match {
    case Counterchain_new(ctrs, nIters) => effectSyms(nIters)
    case _ => super.boundSyms(e)
  }

}

trait ScalaGenCounterExternOps extends ScalaGenEffect {
  val IR: CounterExternOpsExp
  import IR._

  override def emitNode(sym: Sym[Any], rhs: Def[Any]) = rhs match {
    case e@Counter_new(start,end,step,_) =>
      stream.println("val "+quote(sym)+" = "+quote(start)+" until "+quote(end)+" by "+quote(step))

    case e@Counterchain_new(counters, nIter) =>
      emitValDef(sym, "Array(" + counters.map(quote).mkString(", ") + ")")

    case _ => super.emitNode(sym,rhs)
  }
}

trait DotGenCounterExternOps extends DotGenEffect{
  val IR: CounterExternOpsExp
  import IR._

  def emitCtrChain(cchain: Exp[CounterChain]):Unit = {
    val Def(EatReflect(d)) = cchain
    emitCtrChain(cchain.asInstanceOf[Sym[CounterChain]],
                   d.asInstanceOf[Def[Any]])
  }

  def emitCtrChain(sym: Sym[Any], rhs: Def[Any]):Unit = rhs match {
    case e@Counterchain_new(counters, nIter) =>
      if (!emittedCtrChain.contains(sym)) {
        emittedCtrChain += sym
        emit(s"""subgraph cluster_${quote(sym)} {""")
        emit(s""" label=${quote(sym)} """)
        emit(s""" style="rounded, filled" """)
        emit(s""" fillcolor=$counterColor""")
        counters.foreach{ ctr =>
          emit(s"""   ${quote(ctr)}""")
        }
        emit("}")
      }
    case _ =>
  }

  override def emitNode(sym: Sym[Any], rhs: Def[Any]) = rhs match {
    case e@Counter_new(start,end,step,_) =>
      var l = s""""${quote(sym)}"""
      if (quote(start).forall(_.isDigit)) {
        l += "|start=" + quote(start)
      } else {
        emitEdge(start, sym, "start")
      }
      if (quote(end).forall(_.isDigit)) {
        l += "|end=" + quote(end)
      } else {
        emitEdge(end, sym, "end")
      }
      if (quote(step).forall(_.isDigit)) {
        l += "|step=" + quote(step)
      } else {
        emitEdge(step, sym, "step")
      }
      l += "\""
      emit(s"""${quote(sym)} [ label=$l shape="record" style="filled,rounded"
            color=$counterInnerColor ]""")

    case e@Counterchain_new(counters, nIter) =>
      //TODO: check whether parent of cchain is empty, if is emit ctrchain
      if (parentOf(sym).isEmpty) {
        emitCtrChain(sym, rhs)
      }

    case _ => super.emitNode(sym, rhs)
  }
}

