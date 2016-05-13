package dhdl.compiler.ops

import scala.reflect.{Manifest,SourceContext}

import ppl.delite.framework.analysis.{AffineAnalysisExp, AffineAnalyzer}

import dhdl.shared._
import dhdl.shared.ops._
import dhdl.compiler._
import dhdl.compiler.ops._

trait DHDLAffineAnalysisExp extends AffineAnalysisExp {
  this: DHDLExp =>

  def isIndexType(t: Manifest[_]) = isFixPtType(t) && sign(t) && nbits(t.typeArguments(1)) == 32 && nbits(t.typeArguments(2)) == 0

  // Pair of symbols for nodes used in address calculation addition nodes
  override def indexPlusUnapply(x: Exp[Index]): Option[(Exp[Index], Exp[Index])] = x match {
    case Deff(FixPt_Add(a,b)) => Some((a.asInstanceOf[Exp[Index]],b.asInstanceOf[Exp[Index]])) // annoying erasure here
    case _ => None
  }
  // Pair of symbols for nodes used in address calculation multiplication nodes
  override def indexTimesUnapply(x: Exp[Index]): Option[(Exp[Index], Exp[Index])] = x match {
    case Deff(FixPt_Mul(a,b)) => Some((a.asInstanceOf[Exp[Index]],b.asInstanceOf[Exp[Index]]))
    case _ => None
  }
  // List of loop scopes. Each scope contains a list of iterators and blocks to traverse for loop nodes
  override def loopUnapply(x: Exp[Any]): Option[List[(List[Sym[Index]], List[Block[Any]])]] = x match {
    case Deff(Pipe_foreach(cchain, func, inds)) =>
      Some( List(inds -> List(func)) )
    case Deff(Pipe_fold(cchain,accum,fA,iFunc,ld,st,func,rFunc,inds,idx,acc,res,rV)) =>
      Some( List(inds -> List(iFunc,ld,st,func,rFunc)) )
    case Deff(Accum_fold(c1,c2,a,fA,iFunc,func,ld1,ld2,rFunc,st,inds1,inds2,idx,part,acc,res,rV)) =>
      Some( List(inds1 -> List(func), (inds1 ++ inds2) -> List(ld1,ld2,rFunc,st)) )
    case _ => None
  }
  // Memory being read + list of addresses (for N-D access)
  override def readUnapply(x: Exp[Any]): Option[(Exp[Any], List[Exp[Index]])] = x match {
    case Deff(Bram_load(bram,addr)) => Some((bram, accessIndices(x)))
    case _ => None
  }
  // Memory being written + list of addresses (for N-D access)
  override def writeUnapply(x: Exp[Any]): Option[(Exp[Any], List[Exp[Index]])] = x match {
    case Deff(Bram_store(bram,addr,y)) => Some((bram, accessIndices(x)))
    case _ => None
  }
  // Usually None, but allows other exceptions for symbols being loop invariant
  override def invariantUnapply(x: Exp[Index]): Option[Exp[Index]] = x match {
    case Exact(_) => Some(x)  // May not be constant yet but will be in future
    case _ => super.invariantUnapply(x)
  }
}

trait DHDLAffineAnalyzer extends AffineAnalyzer {
  val IR: DHDLAffineAnalysisExp with DHDLExp
  import IR._

  override def traverse(lhs: Exp[Any], rhs: Def[Any]): Unit = lhs match {
    case Bram_load_vector(bram,ofs,len,cchain) => List.fill(lenOf(cchain))(FlexibleAccess)
    case Bram_store_vector(bram,ofs,vec,cchain) => List.fill(lenOf(cchain))(FlexibleAccess)

    case Pipe_fold(cc,a,_,iFunc,ld,st,func,rFunc,inds,idx,acc,res,rV) =>
      super.traverse(lhs,rhs)
      accessPattern(lhs) = List(FlexibleAccess)

    case Accum_fold(cc1,cc2,a,_,iFunc,func,ld1,ld2,rFunc,st,inds1,inds2,idx,part,acc,res,rV) =>
      super.traverse(lhs,rhs)
      accessPattern(lhs) = List(inds2.map{i => LinearAccess(i)})

    case _ => super.traverse(lhs,rhs)
  }
}