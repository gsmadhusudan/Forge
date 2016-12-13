package ppl.dsl.forge
package templates
package compiler

import java.io.{BufferedWriter, FileWriter, PrintWriter}
import scala.tools.nsc.io._
import scala.collection.mutable.ArrayBuffer
import scala.virtualization.lms.common._

import core._
import shared.BaseGenOps
import Utilities._

trait DeliteGenOps extends BaseGenOps {
  this: ForgeCodeGenDelite =>

  val IR: ForgeApplicationRunner with ForgeExp with ForgeOpsExp
  import IR._

  var activeGenerator: CodeGenerator = _
  private var boundArg: String = _  // bound symbol for the captured variable of a block

  //TODO: Better way to check if the string contains block?
  private def containsBlock(b: String): Boolean = {
    if (b.contains("emitBlock")) true
    else false
  }

  /**
   * Create the IR node name for the given DSL op
   * Name is either the op's label (if one was created using label()) or a function of the op's name and the op's group
   */
  def makeOpNodeName(o: Rep[DSLOp], suffix: String = "") = {
    Labels.get(o).map(_.capitalize).getOrElse {
      val i = nameClashId(o, clasher = nameClashesGrp) // was nameClashesSimple - why?
      o.style match {
        case `staticMethod` => o.grp.name + i + "Object_" + sanitize(o.name).capitalize + suffix
        case `directMethod` if o.visibility != publicMethod => sanitize(o.name).capitalize + suffix
        case _ => o.grp.name + i + "_" + sanitize(o.name).capitalize + suffix
      }
    }
  }

  override def quote(x: Exp[Any]): String = x match {
    case Def(QuoteBlockResult(func,args,ret,captured)) =>
      // bind function args to captured args
      var boundStr: String = ""
      var i = 0
      if (!isThunk(func.tpe)) {
        for (a <- args) {
          // have to be careful about automatic string lifting here
          val boundArg_saved = boundArg
          boundArg = replaceWildcards(boundArgName(func,a))
          if (containsBlock(replaceWildcards(captured(i)))) {
            // when the captured variable is another block,
            // declare the varible, emit block, and assign the result to the variable at the end of the block.
            val add = nl + "emitVarDecl(" + replaceWildcards(boundArgName(func,a))+ ".asInstanceOf[Sym[Any]])" + nl + replaceWildcards(captured(i))
            boundStr += add
          }
          else {
            val add: String = (nl + "emitValDef(" + replaceWildcards(boundArgName(func,a)) + ".asInstanceOf[Sym[Any]],\"" + replaceWildcards(captured(i)) + "\")")
            boundStr += add
          }
          boundArg = boundArg_saved
          i += 1
        }
      }

      if (activeGenerator == cpp && boundArg == null && ret != MUnit)
        warn("Block " + func.name + " returns non-unit type result. C++ target may not work properly." + boundStr)

      // the new-line formatting is admittedly weird; we are using a mixed combination of actual new-lines (for string splitting at Forge)
      // and escaped new-lines (for string splitting at Delite), based on how we received strings from string interpolation.
      // FIXME: using inconsistent newline character, not platform independent
      val out = "{ \"" + boundStr +
        nl + "emitBlock(" + func.name + ")" +
        (if (ret != MUnit && boundArg == null)
           (nl + "quote(getBlockResult(" + func.name + "))+\"\\n\"")
         else if (ret != MUnit && boundArg != null)
           (nl + "emitAssignment(" + boundArg + ".asInstanceOf[Sym[Any]], quote(getBlockResult(" + func.name + ")))")
         else ""
        ) +
        nl + " \" }\\n "
       if(ret != MUnit && boundArg != null) "\"" + out + "\"" else out

    case Def(QuoteSeq(argName)) => "Seq("+unquotes(argName+".map(quote).mkString("+quotes(",")+")")+")"

    case Const(s: String) if quoteLiterally => s  // no quotes, wildcards will be replaced later in inline
    case Const(s: String) => replaceWildcards(super.quote(x))  // quote first, then insert wildcards

    case _ => super.quote(x)
  }



  // non-thunk functions use bound sym inputs, so we need to add the bound syms to the args
  // we need both the func name and the func arg name to completely disambiguate the bound symbol, but the unique name gets quite long..
  // could use a local numbering scheme inside each op. should also consider storing the bound syms inside the case class body, instead of the parameter list, which could simplify things.
  def boundArgName(func: Rep[DSLArg], arg: Rep[DSLArg]) = "f_" + func.name  + "_" + simpleArgName(arg)
  def boundArgAnonName(func: Rep[DSLArg], arg: Rep[DSLArg], i: Int) = "f_" + opArgPrefix + i + "_" + simpleArgName(arg)
  def makeArgsWithBoundSyms(args: List[Rep[DSLArg]], opType: OpType) =
    makeArgs(args, t => t match {
      case Def(Arg(name, f@Def(FTpe(fargs,ret,freq)), d2)) if opTypeRequiresBlockify(opType) && !isThunk(f) => (simpleArgName(t) :: fargs.map(a => boundArgName(t,a))).mkString(",")
      case _ => simpleArgName(t)
  })
  def makeArgsWithBoundSymsWithType(args: List[Rep[DSLArg]], opType: OpType, typify: Rep[DSLType] => String = repify, addParen: Boolean = true) =
    makeArgs(args, t => t match {
      case Def(Arg(name, f@Def(FTpe(fargs,ret,freq)), d2)) if opTypeRequiresBlockify(opType) && !isThunk(f) => ((simpleArgName(t) + ": " + typify(t.tpe)) :: fargs.map(a => boundArgName(t,a) + ": " + typify(a.tpe))).mkString(",")
      case _ => argify(t, typify)
  }, addParen)

  def makeOpSimpleNodeNameWithArgs(o: Rep[DSLOp]) = makeOpNodeName(o) + makeArgsWithBoundSyms(o.args, Impls(o))
  def makeOpSimpleNodeNameWithAnonArgs(o: Rep[DSLOp], suffix: String = "") = {
    // scalac typer error unless we break up the expression
    val z = o.args.zipWithIndex.map{ case (a,i) => arg(opArgPrefix + i, a.tpe, a.default) }
    makeOpNodeName(o) + suffix + makeArgsWithBoundSyms(z, Impls(o))
    // makeOpNodeName(o) + suffix + makeArgsWithBoundSyms(o.args.zipWithIndex.map{ case (a,i) => arg(opArgPrefix + i, a.tpe, a.default) })
  }

  // TODO: is this code obsoleted by quote(tpeInst(hkTpe,tpeArg))?? TEST AND REMOVE
  // TODO: tpeArg should be a List that is the same length as the tpePars in hkTpe
  def makeTpeInst(hkTpe: Rep[DSLType], tpeArg: Rep[DSLType]) = hkTpe match {
    case Def(Tpe(s,Nil,stage)) => s // rather lenient, might get strange results in an improperly specified dsl
    case Def(Tpe(s,List(z),stage)) => s + "[" + quote(tpeArg) + "]"
    case Def(Tpe(s,args,stage)) => err("tried to instantiate tpe " + hkTpe.name + " with arg " + tpeArg.name + ", but " + hkTpe.name + " requires " + args.length + " type parameters")
  }

  def makeTpeClsPar(b: TypeClassSignature, t: Rep[DSLType]) = {
    val body = opIdentifierPrefix + "." + b.prefix + quote(t)
    b.wrapper match {
      case Some(w) => w + "(" + body + ")"
      case None => body
    }
  }

  def makeFuncSignature(argTpePars: List[Rep[DSLType]], retTpePar: Rep[DSLType]) = {
    val argStr = if (argTpePars == Nil) "" else argTpePars.map(t => "Rep["+quote(t)+"]").mkString("(",",",")") + " => "
    Some(argStr + "Rep[" + quote(retTpePar) + "]")
  }

  def emitNoCompilerErr(o: Rep[DSLOp], stream: PrintWriter, indent: Int) {
    emitWithIndent("throw new Exception(\"DSL design error: attempted to call library-only method " + o.name + " during staging\")", stream, indent)
  }
  def emitImplMethod(o: Rep[DSLOp], func: Rep[String], postfix: String, returnTpe: Option[String], stream: PrintWriter, indent: Int = 2) {
    emitWithIndent(makeOpImplMethodSignature(o, postfix, returnTpe) + " = {", stream, indent)
    inline(o, func, quoteLiteral).split(nl).foreach { line => emitWithIndent(line, stream, indent+2 )}
    emitWithIndent("}", stream, indent)
    stream.println()
  }

  def requiresImpl(op: Rep[DSLOp]) = Impls(op) match {
    case _:CodeGen | _:Redirect => false
    case _:Getter | _:Setter => false
    case _:Allocates | _:AllocatesFigment => false
    case _ => true
  }

  /**
   * Emit op implementations for composite and single task n
   */
  def emitImpls(uniqueOps: List[Rep[DSLOp]], stream: PrintWriter) {
    for (o <- uniqueOps if requiresImpl(o)) {
      Impls(o) match {
        case single:SingleTask =>
          emitImplMethod(o, single.func, "", None, stream)
        case composite:Composite =>
          emitImplMethod(o, composite.func, "", None, stream)

        case AllocatesRecord(tpe, fields, fieldTpes, init) =>
          emitWithIndent(makeOpImplMethodSignature(o) + " = {", stream, 2)
          emitRecordOp(o, tpe, fields, fieldTpes, init, stream, 4)
          emitWithIndent("}", stream, 2)
          stream.println()

        case map:Map =>
          emitImplMethod(o, map.func, "_map", makeFuncSignature(map.tpePars._1, map.tpePars._2), stream)
        case zip:Zip =>
          emitImplMethod(o, zip.func, "_zip", makeFuncSignature((zip.tpePars._1, zip.tpePars._2), zip.tpePars._3), stream)
        case reduce:Reduce =>
          emitImplMethod(o, reduce.func, "_reduce", makeFuncSignature((reduce.tpePar, reduce.tpePar), reduce.tpePar), stream)
          emitImplMethod(o, reduce.zero, "_zero", makeFuncSignature(Nil, reduce.tpePar), stream)
        case mapreduce:MapReduce =>
          emitImplMethod(o, mapreduce.map, "_map",  makeFuncSignature(mapreduce.tpePars._1, mapreduce.tpePars._2), stream)
          emitImplMethod(o, mapreduce.reduce, "_reduce", makeFuncSignature((mapreduce.tpePars._2, mapreduce.tpePars._2), mapreduce.tpePars._2), stream)
          emitImplMethod(o, mapreduce.zero, "_zero", makeFuncSignature(Nil, mapreduce.tpePars._2), stream)
          if (mapreduce.cond.isDefined) {
            emitImplMethod(o, mapreduce.cond.get, "_cond", makeFuncSignature(mapreduce.tpePars._1, MBoolean), stream)
          }
        case filter:Filter =>
          emitImplMethod(o, filter.cond, "_cond", makeFuncSignature(filter.tpePars._1, MBoolean), stream)
          emitImplMethod(o, filter.func, "_map", makeFuncSignature(filter.tpePars._1, filter.tpePars._2), stream)
        case flatmap:FlatMap =>
          val outCol = getHkTpe(o.retTpe)
          emitImplMethod(o, flatmap.func, "_func", makeFuncSignature(flatmap.tpePars._1, tpeInst(outCol, flatmap.tpePars._2)), stream)
        case gb:GroupBy =>
          if (gb.cond.isDefined) {
            emitImplMethod(o, gb.cond.get, "_cond", makeFuncSignature(gb.tpePars._1, MBoolean), stream)
          }
          emitImplMethod(o, gb.key, "_key", makeFuncSignature(gb.tpePars._1, gb.tpePars._2), stream)
          emitImplMethod(o, gb.map, "_map",  makeFuncSignature(gb.tpePars._1, gb.tpePars._3), stream)
        case gbr:GroupByReduce =>
          if (gbr.cond.isDefined) {
            emitImplMethod(o, gbr.cond.get, "_cond", makeFuncSignature(gbr.tpePars._1, MBoolean), stream)
          }
          emitImplMethod(o, gbr.key, "_key", makeFuncSignature(gbr.tpePars._1, gbr.tpePars._2), stream)
          emitImplMethod(o, gbr.map, "_map",  makeFuncSignature(gbr.tpePars._1, gbr.tpePars._3), stream)
          emitImplMethod(o, gbr.zero, "_zero",  makeFuncSignature(Nil, gbr.tpePars._3), stream)
          emitImplMethod(o, gbr.reduce, "_reduce", makeFuncSignature((gbr.tpePars._3, gbr.tpePars._3), gbr.tpePars._3), stream)
        case foreach:Foreach =>
          emitImplMethod(o, foreach.func, "_func", makeFuncSignature(foreach.tpePar, MUnit), stream)
        case _ =>
      }
    }
  }

  /**
   * Create name of compiler backend base trait for group of DSL operations and names of traits
   * this app mixes in.
   * Base is [Grp]InternalOps if any are not user-facing methods, [Grp]Ops otherwise
   * Checks to see if we need DeliteCollections, DeliteStructs, and/or FigmentOps to be mixed in
   */
  def baseOpsCls(opsGrp: DSLOps) = {
    var base = "BaseFatExp with EffectExp"
    base += { if (opsGrp.ops.exists(_.visibility == privateMethod)) " with " + opsGrp.grp.name + "InternalOps"
              else " with " + opsGrp.name }
    base += ( if (opsGrp.ops.exists(o => grpIsTpe(o.grp) && ForgeCollections.contains(grpAsTpe(o.grp)))) " with DeliteCollectionOpsExp" else "" )
    base += ( if (opsGrp.ops.exists(o => grpIsTpe(o.grp) && DataStructs.contains(grpAsTpe(o.grp)))) " with DeliteStructsExp" else "" )
    base += ( if (opsGrp.ops.exists(isFigmentOp)) " with DeliteFigmentOpsExp" else "" )
    base
  }

  /**
   * Op implementation types with nodes: figment, allocates, single, codegen, and all parallel patterns
   * composite, redirect, setter, and getter are represented using existing nodes
   * groupBy and groupByReduce are represented using several nodes
   */
  def hasIRNode(o: Rep[DSLOp]) = Impls(o) match {
    case _:Composite | _:Redirect | _:Getter | _:Setter | _:AllocatesRecord => false
    case _ => true
  }
  def hasMultipleIRNodes(o: Rep[DSLOp]) = Impls(o) match {
    case _:GroupBy | _:GroupByReduce => true
    case _ => false
  }

  /**
   * Emit internals in the IR node definition to save implicit type class evidence for node type parameters
   * case class Node[T:Manifest](...) extends [opStr] { val _mT = implicitly[Manifest[T]] }
   */
  def emitOpNodeHeader(o: Rep[DSLOp], opStr: String, stream: PrintWriter) {
    stream.println(" extends " + opStr + " {")
    for (targ <- o.tpePars) {
      for (b <- targ.ctxBounds) {
        stream.println("    val " + b.prefix + targ.name + " = implicitly[" + b.name + "[" + targ.name + "]]")
      }
    }
  }
  /**
   * Emit op footer (just closing bracket for now)
   */
  def emitOpNodeFooter(o: Rep[DSLOp], stream: PrintWriter) {
    stream.println("  }")
  }


  def emitGroupByCommonVals(o: Rep[DSLOp], in: Rep[DSLArg], cond: Option[Rep[String]], tpePars: (Rep[DSLType],Rep[DSLType],Rep[DSLType]), stream: PrintWriter) {
    val inDc = ForgeCollections(getHkTpe(in.tpe))
    stream.println()
    stream.println("    val in = " + in.name)
    if (cond.isDefined) {
      stream.println("    def cond = " + makeOpImplMethodNameWithArgs(o, "_cond"))
    }
    else {
      stream.println("    def cond: Exp["+quote(tpePars._1)+"] => Exp[Boolean] = null")
    }
    stream.println("    val size = copyTransformedOrElse(_.size)(" + makeOpMethodName(inDc.size) + "(in))")
    stream.println("    def keyFunc = " + makeOpImplMethodNameWithArgs(o, "_key"))
  }

  def emitGroupByCommonNodes(o: Rep[DSLOp], in: Rep[DSLArg], cond: Option[Rep[String]], tpePars: (Rep[DSLType],Rep[DSLType],Rep[DSLType]), stream: PrintWriter) {
    val inDc = ForgeCollections(getHkTpe(in.tpe))
    val outerColTpe = MArray
    val outDc = ForgeCollections(outerColTpe)

    // keys
    stream.print("  case class " + makeOpNodeName(o, "Keys") + makeTpeParsWithBounds(o.tpePars))
    stream.print(makeOpArgsWithType(o))
    stream.print(makeOpImplicitArgsWithType(o,true))
    emitOpNodeHeader(o, "DeliteOpFilteredGroupByReduce[" + quote(tpePars._1) + "," + quote(tpePars._2) + "," + quote(tpePars._2) + "," + quote(tpeInst(outerColTpe, tpePars._2)) + "]", stream)
    emitGroupByCommonVals(o, in, cond, tpePars, stream)
    stream.println("    def valFunc = keyFunc")
    stream.println("    def reduceFunc = (a,b) => a")
    stream.println("    def zero = unit(null).asInstanceOf["+repify(tpePars._2)+"]")

    // see comment about innerDcArg in library/Ops.scala
    val keysDcArg = if (quote(in.tpe) == quote(outerColTpe)) "in" else "null.asInstanceOf["+repify(tpeInst(outerColTpe, tpePars._2))+"]"
    stream.println("    override def alloc(len: Exp[Int]) = " + makeOpMethodName(outDc.alloc) + makeTpePars(instAllocReturnTpe(outDc.alloc,in.tpe,tpePars._2)) + "("+keysDcArg+", len)")

    emitOpNodeFooter(o, stream)
    stream.println()

    // index
    stream.print("  case class " + makeOpNodeName(o, "Index") + makeTpeParsWithBounds(o.tpePars))
    stream.print(makeOpArgsWithType(o))
    stream.print(makeOpImplicitArgsWithType(o,true))
    emitOpNodeHeader(o, "DeliteOpBuildIndex[" + quote(tpePars._1) + "," + quote(tpePars._2) + ",DeliteIndex[" + quote(tpePars._2) + "]]", stream)
    emitGroupByCommonVals(o, in, cond, tpePars, stream)
    emitOpNodeFooter(o, stream)
    stream.println()
  }


  def opTypeRequiresBlockify(t: OpType) = t match {
    case _:CodeGen | _:Figment => true
    case _ => false
  }

  /**
   * Emit the IR node definition(s) used to represent ops
   */
  def emitIRNodes(uniqueOps: List[Rep[DSLOp]], stream: PrintWriter) {
    def matchChunkInput(x: Any): Int = x match {
      case i:Int => i.asInstanceOf[Int]
      case s:String => -1
      case _ => 0
    }
    // Emit IR nodes for all ops representedy by exactly one node
    for (o <- uniqueOps if hasIRNode(o) && !hasMultipleIRNodes(o)) {
      stream.print("  case class " + makeOpNodeName(o) + makeTpeParsWithBounds(o.tpePars))

      if (opTypeRequiresBlockify(Impls(o)))
        stream.print(makeArgsWithBoundSymsWithType(o.args, Impls(o), blockify))
      else
        stream.print(makeOpArgsWithType(o))

      stream.print(makeOpImplicitArgsWithType(o,true))

      Impls(o) match {
        case codegen:CodeGen =>
          emitOpNodeHeader(o, "Def[" + quote(o.retTpe) + "]", stream)
        case single:SingleTask =>
          emitOpNodeHeader(o, "DeliteOpSingleTask[" + quote(o.retTpe) + "](reifyEffectsHere("+makeOpImplMethodNameWithArgs(o)+"))", stream)
        case Allocates(tpe,init) =>
          emitOpNodeHeader(o, "DeliteStruct[" + quote(o.retTpe) + "]", stream)
          val data = DataStructs(tpe)
          val elemsPure = data.fields.zip(init) map { case ((name,t),i) => ("\""+name+"\"", inline(o,i,quoteLiteral)) }
          val elems = if (o.effect == mutable) elemsPure map { case (k,v) => (k, "var_new("+v+").e") } else elemsPure
          stream.println("    val elems = copyTransformedElems(collection.Seq(" + elems.mkString(",") + "))")

        case AllocatesFigment(tpe,init) =>
          emitOpNodeHeader(o, "Fig[" + quote(o.retTpe) + "]", stream)
        case figment:Figment =>
          emitOpNodeHeader(o, "Fig[" + quote(o.retTpe) + "]", stream)

        case map:Map =>
          val colTpe = getHkTpe(o.retTpe)
          val outDc = ForgeCollections(colTpe)
          val in = o.args.apply(map.argIndex)
          val inDc = ForgeCollections(getHkTpe(in.tpe))
          emitOpNodeHeader(o, "DeliteOpMap[" + quote(map.tpePars._1) + "," + quote(map.tpePars._2) + "," + makeTpeInst(colTpe, map.tpePars._2) + "]", stream)
          stream.println()
          stream.println("    val in = " + in.name)
          stream.println("    def func = " + makeOpImplMethodNameWithArgs(o, "_map"))
          stream.println("    override def alloc(len: Exp[Int]) = " + makeOpMethodName(outDc.alloc) + makeTpePars(instAllocReturnTpe(outDc.alloc,in.tpe,map.tpePars._2)) + "(in, len)")
          stream.println("    val size = copyTransformedOrElse(_.size)(" + makeOpMethodName(inDc.size) + "(in))")
          stream.println("    override val numDynamicChunks = " + matchChunkInput(map.numDynamicChunks))
        case zip:Zip =>
          val colTpe = getHkTpe(o.retTpe)
          val outDc = ForgeCollections(colTpe)
          val inA = o.args.apply(zip.argIndices._1)
          val inDc = ForgeCollections(getHkTpe(inA.tpe))
          emitOpNodeHeader(o, "DeliteOpZipWith[" + quote(zip.tpePars._1) + "," + quote(zip.tpePars._2) + "," + quote(zip.tpePars._3) + "," + makeTpeInst(colTpe,zip.tpePars._3) + "]", stream)
          stream.println()
          stream.println("    val inA = " + inA.name)
          stream.println("    val inB = " + o.args.apply(zip.argIndices._2).name)
          stream.println("    def func = " + makeOpImplMethodNameWithArgs(o, "_zip"))
          stream.println("    override def alloc(len: Exp[Int]) = " + makeOpMethodName(outDc.alloc) + makeTpePars(instAllocReturnTpe(outDc.alloc,inA.tpe,zip.tpePars._3)) + "(inA, len)")
          stream.println("    val size = copyTransformedOrElse(_.size)(" + makeOpMethodName(inDc.size) + "(inA))")
          stream.println("    override val numDynamicChunks = " + matchChunkInput(zip.numDynamicChunks))
        case reduce:Reduce =>
          val col = o.args.apply(reduce.argIndex)
          val dc = ForgeCollections(getHkTpe(col.tpe))
          emitOpNodeHeader(o, "DeliteOpReduce[" + quote(reduce.tpePar) + "]", stream)
          stream.println()
          stream.println("    val in = " + col.name)
          stream.println("    def func = " + makeOpImplMethodNameWithArgs(o, "_reduce"))
          stream.println("    def zero = " + makeOpImplMethodNameWithArgs(o, "_zero"))
          stream.println("    val size = copyTransformedOrElse(_.size)(" + makeOpMethodName(dc.size) + "(in))")
          stream.println("    override val numDynamicChunks = " + matchChunkInput(reduce.numDynamicChunks))
        case mapreduce:MapReduce =>
          val col = o.args.apply(mapreduce.argIndex)
          val dc = ForgeCollections(getHkTpe(col.tpe))
          if (mapreduce.cond.isDefined) {
            emitOpNodeHeader(o, "DeliteOpFilterReduce[" + quote(mapreduce.tpePars._1) + "," + quote(mapreduce.tpePars._2) + "]", stream)
          }
          else {
            emitOpNodeHeader(o, "DeliteOpMapReduce[" + quote(mapreduce.tpePars._1) + "," + quote(mapreduce.tpePars._2) + "]", stream)
          }
          stream.println()
          stream.println("    val in = " + col.name)
          stream.println("    def zero = " + makeOpImplMethodNameWithArgs(o, "_zero"))
          stream.println("    def reduce = " + makeOpImplMethodNameWithArgs(o, "_reduce"))
          // kind of silly, but DeliteOpFilterReduce and DeliteOpMapReduce have different names for the mapping function
          if (mapreduce.cond.isDefined) {
            stream.println("    def func = " + makeOpImplMethodNameWithArgs(o, "_map"))
            stream.println("    def cond = " + makeOpImplMethodNameWithArgs(o, "_cond"))
          }
          else {
            stream.println("    def map = " + makeOpImplMethodNameWithArgs(o, "_map"))
          }
          stream.println("    val size = copyTransformedOrElse(_.size)(" + makeOpMethodName(dc.size) + "(in))")
          stream.println("    override val numDynamicChunks = " + matchChunkInput(mapreduce.numDynamicChunks))
        case filter:Filter =>
          val colTpe = getHkTpe(o.retTpe)
          val outDc = ForgeCollections(colTpe)
          val in = o.args.apply(filter.argIndex)
          val inDc = ForgeCollections(getHkTpe(in.tpe))
          emitOpNodeHeader(o, "DeliteOpFilter[" + quote(filter.tpePars._1) + "," + quote(filter.tpePars._2) + "," + makeTpeInst(colTpe,filter.tpePars._2) + "]", stream)
          stream.println()
          stream.println("    val in = " + in.name)
          stream.println("    def cond = " + makeOpImplMethodNameWithArgs(o, "_cond"))
          stream.println("    def func = " + makeOpImplMethodNameWithArgs(o, "_map"))
          stream.println("    override def alloc(len: Exp[Int]) = " + makeOpMethodName(outDc.alloc) + makeTpePars(instAllocReturnTpe(outDc.alloc,in.tpe,filter.tpePars._2)) + "(in, len)")
          stream.println("    val size = copyTransformedOrElse(_.size)(" + makeOpMethodName(inDc.size) + "(in))")
          stream.println("    override val numDynamicChunks = " + matchChunkInput(filter.numDynamicChunks))
        case flatmap:FlatMap =>
          val colTpe = getHkTpe(o.retTpe)
          val outDc = ForgeCollections(colTpe)
          val in = o.args.apply(flatmap.argIndex)
          val inDc = ForgeCollections(getHkTpe(in.tpe))
          emitOpNodeHeader(o, "DeliteOpFlatMap[" + quote(flatmap.tpePars._1) + "," + quote(flatmap.tpePars._2) + "," + makeTpeInst(colTpe,flatmap.tpePars._2) + "]", stream)
          stream.println()
          stream.println("    val in = " + in.name)
          stream.println("    def func = " + makeOpImplMethodNameWithArgs(o, "_func"))
          stream.println("    override def alloc(len: Exp[Int]) = " + makeOpMethodName(outDc.alloc) + makeTpePars(instAllocReturnTpe(outDc.alloc,in.tpe,flatmap.tpePars._2)) + "(in, len)")
          stream.println("    val size = copyTransformedOrElse(_.size)(" + makeOpMethodName(inDc.size) + "(in))")
          stream.println("    override val numDynamicChunks = " + matchChunkInput(flatmap.numDynamicChunks))
        case foreach:Foreach =>
          val col = o.args.apply(foreach.argIndex)
          val dc = ForgeCollections(getHkTpe(col.tpe))
          emitOpNodeHeader(o, "DeliteOpForeach[" + quote(foreach.tpePar) + "]", stream)
          stream.println()
          stream.println("    val in = " + col.name)
          stream.println("    def func = " + makeOpImplMethodNameWithArgs(o, "_func"))
          stream.println("    def sync = n => unit(List())")
          stream.println("    val size = copyTransformedOrElse(_.size)(" + makeOpMethodName(dc.size) + "(in))")
          stream.println("    override val numDynamicChunks = " + matchChunkInput(foreach.numDynamicChunks))
      }
      emitOpNodeFooter(o, stream)
      stream.println()
    }

    // Emit IR nodes for special cases (groupBy and groupByReduce)
    for (o <- uniqueOps if hasMultipleIRNodes(o)) {
      Impls(o) match {
        case gb:GroupBy =>
          val in = o.args.apply(gb.argIndex)
          val inDc = ForgeCollections(getHkTpe(in.tpe))
          // val outerColTpe = getHkTpe(o.retTpe)
          val outerColTpe = MArray
          val outDc = ForgeCollections(outerColTpe)
          val innerColTpe = getHkTpe(gb.tpePars._4)
          val innerDc = ForgeCollections(innerColTpe)

          emitGroupByCommonNodes(o, in, gb.cond, (gb.tpePars._1,gb.tpePars._2,gb.tpePars._3), stream)

          // values
          stream.print("  case class " + makeOpNodeName(o) + makeTpeParsWithBounds(o.tpePars))
          stream.print(makeOpArgsWithType(o))
          stream.print(makeOpImplicitArgsWithType(o,true))
          emitOpNodeHeader(o, "DeliteOpFilteredGroupBy[" + quote(gb.tpePars._1) + "," + quote(gb.tpePars._2) + "," + quote(gb.tpePars._3) + "," + quote(tpeInst(innerColTpe, gb.tpePars._3)) + "," + makeTpeInst(outerColTpe,tpeInst(innerColTpe, gb.tpePars._3)) + "]", stream)
          emitGroupByCommonVals(o, in, gb.cond, (gb.tpePars._1,gb.tpePars._2,gb.tpePars._3), stream)
          stream.println("    def valFunc = " + makeOpImplMethodNameWithArgs(o, "_map"))

          val innerDcArg = if (quote(in.tpe) == quote(innerColTpe)) "in" else "null.asInstanceOf["+repify(tpeInst(innerColTpe, gb.tpePars._3))+"]"
          stream.println("    override def allocI(len: Exp[Int]) = " + makeOpMethodName(innerDc.alloc) + makeTpePars(instAllocReturnTpe(innerDc.alloc, in.tpe, gb.tpePars._3)) + "("+innerDcArg+", len)")

          val outDcArg = if (quote(in.tpe) == quote(outerColTpe)) "in" else "null.asInstanceOf["+repify(tpeInst(outerColTpe, tpeInst(innerColTpe, gb.tpePars._3)))+"]"
          stream.println("    override def alloc(len: Exp[Int]) = " + makeOpMethodName(outDc.alloc) + makeTpePars(instAllocReturnTpe(outDc.alloc, in.tpe, tpeInst(innerColTpe, gb.tpePars._3))) + "("+outDcArg+", len)")

          emitOpNodeFooter(o, stream)
          stream.println()

        case gbr:GroupByReduce =>
          val in = o.args.apply(gbr.argIndex)
          val inDc = ForgeCollections(getHkTpe(in.tpe))
          // val outerColTpe = getHkTpe(o.retTpe)
          val outerColTpe = MArray
          val outDc = ForgeCollections(outerColTpe)

          emitGroupByCommonNodes(o, in, gbr.cond, gbr.tpePars, stream)

          stream.print("  case class " + makeOpNodeName(o) + makeTpeParsWithBounds(o.tpePars))
          stream.print(makeOpArgsWithType(o))
          stream.print(makeOpImplicitArgsWithType(o,true))
          emitOpNodeHeader(o, "DeliteOpFilteredGroupByReduce[" + quote(gbr.tpePars._1) + "," + quote(gbr.tpePars._2) + "," + quote(gbr.tpePars._3) + "," + makeTpeInst(outerColTpe,gbr.tpePars._3) + "]", stream)
          emitGroupByCommonVals(o, in, gbr.cond, gbr.tpePars, stream)
          stream.println("    def valFunc = " + makeOpImplMethodNameWithArgs(o, "_map"))
          stream.println("    def zero = " + makeOpImplMethodNameWithArgs(o, "_zero"))
          stream.println("    def reduceFunc = " + makeOpImplMethodNameWithArgs(o, "_reduce"))

          val outDcArg = if (getHkTpe(in.tpe) == getHkTpe(outerColTpe)) "in" else "null.asInstanceOf["+repify(tpeInst(outerColTpe, gbr.tpePars._3))+"]"
          stream.println("    override def alloc(len: Exp[Int]) = " + makeOpMethodName(outDc.alloc) + makeTpePars(instAllocReturnTpe(outDc.alloc,in.tpe,gbr.tpePars._3)) + "("+outDcArg+", len)")

          emitOpNodeFooter(o, stream)
          stream.println()
      }
    }
    stream.println()
  }

  /**
   * Emit helper methods that construct IR nodes
   * Shouldn't duplicate node definitions, so ops in uniqueOps should all be distinct
   * For codegen and figment nodes, create bound variables and reify function arguments
   */
  def emitNodeConstructors(uniqueOps: List[Rep[DSLOp]], stream: PrintWriter) {
    for (o <- uniqueOps if !isRedirect(o)) {
      stream.println("  " + makeOpMethodSignature(o) + " = {")
      val summary = scala.collection.mutable.ArrayBuffer[String]()

      if (opTypeRequiresBlockify(Impls(o))) {
        for (arg <- o.args) {
          arg match {
            case Def(Arg(name, f@Def(FTpe(args,ret,freq)), d2)) =>
              stream.println()
              for (s <- args if s.tpe != byName) {
                emitWithIndent("val " + boundArgName(arg,s) + " = fresh[" + quote(s.tpe) + "]", stream, 4)
              }
              val fargs = if (!isThunk(f)) makeArgs(args, a => boundArgName(arg,a)) else ""
              emitWithIndent("val b_" + name + " = reifyEffects(" + name + fargs + ")", stream, 4)
              emitWithIndent("val sb_" + name + " = summarizeEffects(b_" + name + ")", stream, 4)
              summary += "sb_"+name
            case _ =>
          }
        }
      }

      def summarizeEffects(s: scala.collection.mutable.ArrayBuffer[String]): String = {
        if (s.length == 0) ""
        else {
          val rest = summarizeEffects(s.tail)
          if (rest == "") s.head
          else s.head + " andThen ((" + rest + " andThen " + s.head + ").star)"
        }
      }

      val hasEffects = summary.length > 0

      // composites, getters and setters are currently inlined
      // In the future, to support pattern matching and optimization, we should implement these as figments and use lowering transformers
      Impls(o) match {
        case _:AllocatesRecord => emitWithIndent(makeOpImplMethodNameWithArgs(o), stream, 4)
        case c:Composite => emitWithIndent(makeOpImplMethodNameWithArgs(o), stream, 4)
        case g@Getter(structArgIndex,field) =>
          val struct = o.args.apply(structArgIndex)
          val fieldTpe = DataStructs(getHkTpe(struct.tpe)).fields.find(t => t._1 == field).get.tpe
          emitWithIndent("field["+quote(fieldTpe)+"]("+inline(o,quotedArg(struct.name),quoteLiteral)+",\""+field+"\")", stream, 4)
        case s@Setter(structArgIndex,field,value) =>
          val struct = o.args.apply(structArgIndex)
          val fieldTpe = DataStructs(getHkTpe(struct.tpe)).fields.find(t => t._1 == field).get.tpe
          emitWithIndent("field_update["+quote(fieldTpe)+"]("+inline(o,quotedArg(struct.name),quoteLiteral)+",\""+field+"\","+inline(o,value,quoteLiteral)+")", stream, 4)
        case _:GroupBy | _:GroupByReduce =>
          emitWithIndent("val keys = " + makeEffectAnnotation(o.effect,o) + "(" + makeOpNodeName(o, "Keys") + makeTpePars(o.tpePars) + makeOpArgs(o) + makeOpImplicitArgs(o) + ")", stream, 4)
          emitWithIndent("val index = " + makeEffectAnnotation(o.effect,o) + "(" + makeOpNodeName(o, "Index") + makeTpePars(o.tpePars) + makeOpArgs(o) + makeOpImplicitArgs(o) + ")", stream, 4)
          emitWithIndent("val values = " + makeEffectAnnotation(o.effect,o) + "(" + makeOpNodeName(o) + makeTpePars(o.tpePars) + makeOpArgs(o) + makeOpImplicitArgs(o) + ")", stream, 4)
          emitWithIndent(makeEffectAnnotation(pure,o) + "(DeliteMapNewImm(keys, values, index, darray_length(values)))", stream, 4)
        case _ if hasEffects =>
          // if (o.effect != simple) { err("don't know how to generate non-simple effects with functions") }
          val prologue = if (o.effect == simple) " andAlso Simple()" else ""
          val args = "(" + o.args.flatMap(a => a match {
            case Def(Arg(name, f@Def(FTpe(args,ret,freq)), d2)) =>
              val freshSyms = if (isThunk(f)) Nil else args.map(b => boundArgName(a,b))
              ("b_" + name) :: freshSyms
            case Def(Arg(name, _, _)) => List(name)
          }).mkString(",") + ")"
          emitWithIndent(makeEffectAnnotation(simple,o) + "(" + makeOpNodeName(o) + makeTpePars(o.tpePars) + args + makeOpImplicitArgs(o) + ", " + summarizeEffects(summary) + prologue + ")", stream, 4)
        case _ =>
          emitWithIndent(makeEffectAnnotation(o.effect,o) + "(" + makeOpNodeName(o) + makeTpePars(o.tpePars) + makeOpArgs(o) + makeOpImplicitArgs(o) + ")", stream, 4)
      }
      emitWithIndent("}", stream, 2)
    }
  }

  def emitSyms(uniqueOps: List[Rep[DSLOp]], stream: PrintWriter) {
    if (uniqueOps.exists(o => o.args.exists(t => t match { case Def(Arg(_, Def(FTpe(a,b,freq)), _)) => true; case _ => false}))) {
      emitBlockComment("Syms", stream, indent=2)

      var symsBuf      = "override def syms(e: Any): List[Sym[Any]] = e match {" + nl
      var boundSymsBuf = "override def boundSyms(e: Any): List[Sym[Any]] = e match {" + nl
      var symsFreqBuf  = "override def symsFreq(e: Any): List[(Sym[Any], Double)] = e match {" + nl

      def makeSym(o: Rep[DSLOp], wrap: String, addFreq: Boolean = false) = {
        val symsArgs = o.args.collect {
          case t@Def(Arg(name, Def(FTpe(args,ret,freq)), d2)) => (freq,name,args.filterNot(_.tpe == byName).map(a => boundArgName(t,a)))
          case Def(Arg(name,tpe,d2)) => (normal,name,Nil)
        }
        if (hasFuncArgs(o)) {
          var symsArgsStr = symsArgs.map { case (f,name,bound) => wrap + (if (addFreq) makeFrequencyAnnotation(f) else "") + "(" + name + ")" }.mkString(":::")
          if (wrap == "effectSyms") symsArgsStr += " ::: List(" + symsArgs.flatMap(t => t._3.map(e => e + ".asInstanceOf[Sym[Any]]")).mkString(",") + ")"
          "    case " + makeOpSimpleNodeNameWithArgs(o) + " => " + symsArgsStr + nl
        }
        else ""
      }

      for (o <- uniqueOps if opTypeRequiresBlockify(Impls(o))) {
        symsBuf += makeSym(o, "syms")
        boundSymsBuf += makeSym(o, "effectSyms")
        symsFreqBuf += makeSym(o, "", addFreq = true)
      }

      symsBuf      += "    case _ => super.syms(e)" + nl + "  }"
      boundSymsBuf += "    case _ => super.boundSyms(e)" + nl + "  }"
      symsFreqBuf  += "    case _ => super.symsFreq(e)" + nl + "  }"

      for (buf <- List(symsBuf,boundSymsBuf,symsFreqBuf)) emitWithIndent(buf,stream,2)
    }
  }

  def emitAliasInfo(uniqueOps: List[Rep[DSLOp]], stream: PrintWriter) {
    if (uniqueOps.exists(o => hasIRNode(o) && o.aliasHint != nohint)) {
      emitBlockComment("Aliases / Sharing", stream, indent=2)

      var aliasBuf    = "override def aliasSyms(e: Any): List[Sym[Any]] = e match {" + nl
      var containBuf  = "override def containSyms(e: Any): List[Sym[Any]] = e match {" + nl
      var extractBuf  = "override def extractSyms(e: Any): List[Sym[Any]] = e match {" + nl
      var copyBuf     = "override def copySyms(e: Any): List[Sym[Any]] = e match {" + nl

      def makeAliasAnnotation(o: Rep[DSLOp], args: List[Int]) = {
        val rhs = if (args == Nil) "Nil" else args.map(i => "syms(" + o.args.apply(i).name + ")").mkString(":::")        // TODO
        "    case " + makeOpSimpleNodeNameWithArgs(o) + " => " + rhs + nl
      }

      def makeAllAliasAnnotations(o: Rep[DSLOp], aliasSyms: Option[List[Int]], containSyms: Option[List[Int]], extractSyms: Option[List[Int]], copySyms: Option[List[Int]]) = {
        aliasSyms.foreach   { l => aliasBuf   += makeAliasAnnotation(o,l) }
        containSyms.foreach { l => containBuf += makeAliasAnnotation(o,l) }
        extractSyms.foreach { l => extractBuf += makeAliasAnnotation(o,l) }
        copySyms.foreach    { l => copyBuf    += makeAliasAnnotation(o,l) }
      }

      for (o <- uniqueOps if hasIRNode(o) && o.aliasHint != nohint) {
        o.aliasHint match {
          case AliasCopies(z) =>
            if (o.args.length == z.length) makeAllAliasAnnotations(o, Some(Nil), Some(Nil), Some(Nil), Some(z)) // == aliasesNone
            else makeAllAliasAnnotations(o, None, None, None, Some(z))

          case AliasInfo(al,co,ex,cp) => makeAllAliasAnnotations(o,al,co,ex,cp)
        }
      }

      aliasBuf   += "    case _ => super.aliasSyms(e)" + nl + "  }"
      containBuf += "    case _ => super.containSyms(e)" + nl + "  }"
      extractBuf += "    case _ => super.extractSyms(e)" + nl + "  }"
      copyBuf    += "    case _ => super.copySyms(e)" + nl + "  }"

      for (buf <- List(aliasBuf,containBuf,extractBuf,copyBuf)) emitWithIndent(buf,stream,2)
    }
  }


  // Hack for dealing with "immediate" DSL types (e.g. Lists of Reps)
  // TODO: Doesn't cover arbitrarily nested cases, e.g. List[List[Rep[Int]]]
  def makeTransformedArg(idx: Int, tpe: Rep[DSLType], xf: String = "f", argName: Option[String] = None): String = {
    val arg = argName.getOrElse(opArgPrefix+idx)
    tpe.stage match {
      case `compile` if tpe.name.startsWith("List") || tpe.name.startsWith("Seq") || tpe.name.startsWith("Option") =>
        arg+".map{x => " + makeTransformedArg(idx,tpe.tpeArgs.head,xf,Some("x")) +  " }"
      case `compile` if !tpe.name.startsWith("Rep") && !tpe.name.startsWith("Exp") => arg
      case _ => xf + "(" + arg + ")"
    }
  }

  def makeTransformedArgs(o: Rep[DSLOp], xf: String = "f", addParen: Boolean = true) = {
    val xformArgs = o.args.zipWithIndex.flatMap{case (arg,idx) => arg match {
      case Def(Arg(name, f@Def(FTpe(args,ret,freq)), d2)) if opTypeRequiresBlockify(Impls(o)) && !isThunk(f) => xf + "("+opArgPrefix+idx+")" :: args.map(a => boundArgAnonName(arg,a,idx))
      // -- workaround for apparent scalac bug (GADT skolem type error), with separate cases for regular tpes and function tpes. this may be too restrictive and miss cases we haven't seen yet that also trigger the bug.
      case Def(Arg(name, f@Def(FTpe(args,ret,freq)), d2)) if isTpePar(o.retTpe) && !isThunk(f) && args.forall(a => a.tpe == o.retTpe || !isTpePar(a.tpe)) && ret == o.retTpe => List(xf + "("+opArgPrefix+idx+".asInstanceOf[" + repify(f).replaceAllLiterally(repify(o.retTpe), "Rep[A]") + "])")
      case Def(Arg(name, tpe, d2)) if !isFuncArg(arg) && isTpePar(o.retTpe) && tpe.tpePars.length == 1 && tpe.tpePars.apply(0) == o.retTpe => List(xf + "("+opArgPrefix+idx+".asInstanceOf[Rep[" + tpe.name + "[A]]])")
      // -- end workaround
      case Def(Arg(_, tpe, _)) => List(makeTransformedArg(idx, tpe, xf))
    }}
    if (addParen) xformArgs.mkString("(",",",")") else xformArgs.mkString(",")
  }
  def makeTransformedImplicits(o: Rep[DSLOp], xf: String = "f") = {
    o.tpePars.flatMap(t => t.ctxBounds.map(b => makeTpeClsPar(b,t))) ++
    o.implicitArgs.map { a =>
      val argName = opIdentifierPrefix + "." + a.name
      if (isTpeClass(a.tpe)) asTpeClass(a.tpe).signature.wrapper.getOrElse("") + "(" + argName + ")" else argName
    }
  }

  def emitMirrors(uniqueOps: List[Rep[DSLOp]], stream: PrintWriter) {
    emitBlockComment("Mirroring", stream, indent=2)
    stream.println("  override def mirror[A:Manifest](e: Def[A], f: Transformer)(implicit pos: SourceContext): Exp[A] = (e match {")

    for (o <- uniqueOps if hasIRNode(o)) {
      // helpful identifiers
      val xformArgs = makeTransformedArgs(o)
      val implicits = makeTransformedImplicits(o)
      val implicitsWithParens = if (implicits.length == 0) "" else implicits.mkString("(",",",")")

      def emitDeliteOpPureMirror(suffix: String = "") {
        stream.print("    case " + opIdentifierPrefix + "@" + makeOpSimpleNodeNameWithAnonArgs(o, suffix) + " => ")
        stream.print("reflectPure(new { override val original = Some(f," + opIdentifierPrefix + ") } with " + makeOpNodeName(o, suffix) + xformArgs + implicitsWithParens + ")")
        stream.println("(mtype(manifest[A]), pos)")
      }

      def emitDeliteOpEffectfulMirror(suffix: String = "") {
        stream.print("    case Reflect(" + opIdentifierPrefix + "@" + makeOpSimpleNodeNameWithAnonArgs(o, suffix) + ", u, es) => ")
        stream.print("reflectMirrored(Reflect(new { override val original = Some(f," + opIdentifierPrefix + ") } with " + makeOpNodeName(o, suffix) + xformArgs + implicitsWithParens)
        stream.print(", mapOver(f,u), f(es)))")
        stream.println("(mtype(manifest[A]), pos)")
      }

      def emitNodePureMirror() {
        stream.print("    case " + opIdentifierPrefix + "@" + makeOpSimpleNodeNameWithAnonArgs(o) + " => ")
        // Pure version with no function arguments uses smart constructor
        if (!hasFuncArgs(o)) {
          stream.print(makeOpMethodName(o) + xformArgs)
          if (needOverload(o) || implicits.length > 0) {
            stream.print("(")
            if (implicits.length > 0) stream.print(implicits.mkString(","))
            if (needOverload(o)) {
              // NOTE: We may need to supply an explicit Overload parameter for the smart constructor
              // Also relies on convention established in implicitArgsWithOverload (overload guaranteed to always be last implicit)
              val overload = implicitArgsWithOverload(o).last
              if (implicits.length > 0) stream.print(",")
              stream.print(quote(overload))
            }
            stream.println(")")
          }
        }
        else {
          // Pure version with function arguments
          stream.print("reflectPure(" + makeOpNodeName(o) + xformArgs + implicitsWithParens + ")")
          stream.println("(mtype(manifest[A]), pos)")
        }
      }
      def emitNodeEffectfulMirror() {
        stream.print("    case Reflect(" + opIdentifierPrefix + "@" + makeOpSimpleNodeNameWithAnonArgs(o) + ", u, es) => ")
        stream.print("reflectMirrored(Reflect(" + makeOpNodeName(o) + xformArgs + implicitsWithParens + ", mapOver(f,u), f(es)))")
        stream.println("(mtype(manifest[A]), pos)")
      }

      Impls(o) match {
        case codegen:CodeGen =>
          emitNodePureMirror()
          emitNodeEffectfulMirror()

        case figment:Figment =>
          emitNodePureMirror()
          emitNodeEffectfulMirror()

        case _:GroupBy | _:GroupByReduce =>
          emitDeliteOpPureMirror("Keys")
          emitDeliteOpEffectfulMirror("Keys")
          emitDeliteOpPureMirror("Index")
          emitDeliteOpEffectfulMirror("Index")
          emitDeliteOpPureMirror()
          emitDeliteOpEffectfulMirror()
        case _:DeliteOpType =>
          emitDeliteOpPureMirror()
          emitDeliteOpEffectfulMirror()
        case _ => // no mirror
      }
    }
    stream.println("    case _ => super.mirror(e, f)")
    stream.println("  }).asInstanceOf[Exp[A]]")
  }

  def emitPropagationRules(ops: List[Rep[DSLOp]], stream: PrintWriter) {
    val rules = ops.flatMap{op => PropagationRules.get(op).map((op,_)) }
    if (!rules.isEmpty) {
      emitBlockComment("Metadata propagation rules", stream)
      stream.println("  override def propagate(lhs: Exp[Any], rhs: Def[Any]) = rhs match {")
      for ((op,rule) <- rules) {
        if (!hasIRNode(op) || hasMultipleIRNodes(op)) {
            err("Cannot create propagation rule for op " + op.name + ": Op must be represented by exactly one IR node")
        }
        emitTraversalRules(op, rule, stream, 4)
      }
      stream.println("    case _ => super.propagate(lhs,rhs)")
      stream.println("  }")
    }
  }

  /**
   * Emit Delite collection function definitions for parallel collections in the given op group
   */
  def emitDeliteCollection(classes: List[Rep[DSLType]], stream: PrintWriter) {
    if (classes.length > 0 && classes.exists(c => ForgeCollections.contains(c))) {
      emitBlockComment("Delite collection", stream, indent=2)
      var dcSizeStream = ""
      var dcApplyStream = ""
      var dcUpdateStream = ""
      var dcParallelizationStream = ""
      var dcSetLogicalSizeStream = ""
      var dcAppendableStream = ""
      var dcAppendStream = ""
      var dcAllocStream = ""
      var dcCopyStream = ""

      var firstCol = true
      var firstBuf = true
      for (tpe <- classes if ForgeCollections.contains(tpe)) {
        val dc = ForgeCollections(tpe)
        val isTpe = "is"+tpe.name
        def asTpe = "as"+tpe.name
        val colTpe = makeTpeInst(tpe, tpePar("A"))
        val a = if (dc.tpeArg.tp.runtimeClass == classOf[TypePar]) "A" else quote(dc.tpeArg) // hack!

        stream.println("  def " + isTpe + "Tpe(x: Manifest[_])(implicit ctx: SourceContext) = isSubtype(x.erasure,classOf["+makeTpeInst(tpe, tpePar("_"))+"])")
        stream.println("  def " + isTpe + "[A](x: Exp[DeliteCollection[A]])(implicit ctx: SourceContext) = " + isTpe + "Tpe(x.tp)")
        stream.println("  def " + asTpe + "[A](x: Exp[DeliteCollection[A]])(implicit ctx: SourceContext) = x.asInstanceOf[Exp["+colTpe+"]]")
        stream.println()

        val prefix = if (firstCol) "    if " else "    else if "
        dcSizeStream +=   prefix + "(" + isTpe + "(x)) " + makeOpMethodName(dc.size) + "(" + asTpe + "(x))" + nl
        dcApplyStream +=  prefix + "(" + isTpe + "(x)) " + makeOpMethodName(dc.apply) + "(" + asTpe + "(x), n).asInstanceOf[Exp[A]]" + nl
        dcUpdateStream += prefix + "(" + isTpe + "(x)) " + makeOpMethodName(dc.update) + "(" + asTpe + "(x), n, y.asInstanceOf[Exp["+a+"]])" + nl

        if (dc.isInstanceOf[ParallelCollectionBuffer]) {
          val dcb = dc.asInstanceOf[ParallelCollectionBuffer]
          val appendTpe = if (isTpePar(dcb.appendable.args.apply(2).tpe)) "y" else "y.asInstanceOf[Exp["+quote(dcb.appendable.args.apply(2).tpe)+"]]"
          val allocTpePars = instAllocReturnTpe(dcb.alloc, ephemeralTpe(tpe.name, List(tpePar("A"))), tpePar("A"))
          val prefix = if (firstBuf) "    if " else "    else if "

          // dcParallelizationStream += "    if (" + isTpe + "(x)) " + makeOpMethodName(dcb.parallelization) + "(" + asTpe + "(x), hasConditions)" + nl
          dcParallelizationStream += prefix + "(" + isTpe + "(x)) { if (hasConditions) ParSimpleBuffer else ParFlat } // TODO: always generating this right now" + nl
          dcSetLogicalSizeStream +=  prefix + "(" + isTpe + "(x)) " + makeOpMethodName(dcb.setSize) + "(" + asTpe + "(x), y)" + nl
          dcAppendableStream +=      prefix + "(" + isTpe + "(x)) " + makeOpMethodName(dcb.appendable) + "(" + asTpe + "(x), i, "+appendTpe+")" + nl
          dcAppendStream +=          prefix + "(" + isTpe + "(x)) " + makeOpMethodName(dcb.append) + "(" + asTpe + "(x), i, "+appendTpe+")" + nl
          dcAllocStream +=           prefix + "(" + isTpe + "(x)) " + makeOpMethodName(dcb.alloc) + makeTpePars(allocTpePars) + "(" + asTpe + "(x), size).asInstanceOf[Exp[CA]]" + nl
          dcCopyStream +=            prefix + "(" + isTpe + "(src) && " + isTpe + "(dst)) " + makeOpMethodName(dcb.copy) + "(" + asTpe + "(src), srcPos, " + asTpe + "(dst), dstPos, size)" + nl
          firstBuf = false
        }
        firstCol = false
      }

      if (!firstCol) {
        stream.println("  override def dc_size[A:Manifest](x: Exp[DeliteCollection[A]])(implicit ctx: SourceContext) = {")
        stream.print(dcSizeStream)
        stream.println("    else super.dc_size(x)")
        stream.println("  }")
        stream.println()
        stream.println("  override def dc_apply[A:Manifest](x: Exp[DeliteCollection[A]], n: Exp[Int])(implicit ctx: SourceContext) = {")
        stream.print(dcApplyStream)
        stream.println("    else super.dc_apply(x,n)")
        stream.println("  }")
        stream.println()
        stream.println("  override def dc_update[A:Manifest](x: Exp[DeliteCollection[A]], n: Exp[Int], y: Exp[A])(implicit ctx: SourceContext) = {")
        stream.print(dcUpdateStream)
        stream.println("    else super.dc_update(x,n,y)")
        stream.println("  }")
        stream.println()
      }
      if (!firstBuf) {
        stream.println("  override def dc_parallelization[A:Manifest](x: Exp[DeliteCollection[A]], hasConditions: Boolean)(implicit ctx: SourceContext) = {")
        stream.print(dcParallelizationStream)
        stream.println("    else super.dc_parallelization(x, hasConditions)")
        stream.println("  }")
        stream.println()
        stream.println("  override def dc_set_logical_size[A:Manifest](x: Exp[DeliteCollection[A]], y: Exp[Int])(implicit ctx: SourceContext) = {")
        stream.print(dcSetLogicalSizeStream)
        stream.println("    else super.dc_set_logical_size(x,y)")
        stream.println("  }")
        stream.println()
        stream.println("  override def dc_appendable[A:Manifest](x: Exp[DeliteCollection[A]], i: Exp[Int], y: Exp[A])(implicit ctx: SourceContext) = {")
        stream.print(dcAppendableStream)
        stream.println("    else super.dc_appendable(x,i,y)")
        stream.println("  }")
        stream.println()
        stream.println("  override def dc_append[A:Manifest](x: Exp[DeliteCollection[A]], i: Exp[Int], y: Exp[A])(implicit ctx: SourceContext) = {")
        stream.print(dcAppendStream)
        stream.println("    else super.dc_append(x,i,y)")
        stream.println("  }")
        stream.println()
        stream.println("  override def dc_alloc[A:Manifest,CA<:DeliteCollection[A]:Manifest](x: Exp[CA], size: Exp[Int])(implicit ctx: SourceContext) = {")
        stream.print(dcAllocStream)
        stream.println("    else super.dc_alloc[A,CA](x,size)")
        stream.println("  }")
        stream.println()
        stream.println("  override def dc_copy[A:Manifest](src: Exp[DeliteCollection[A]], srcPos: Exp[Int], dst: Exp[DeliteCollection[A]], dstPos: Exp[Int], size: Exp[Int])(implicit ctx: SourceContext) = {")
        stream.print(dcCopyStream)
        stream.println("    else super.dc_copy(src,srcPos,dst,dstPos,size)")
        stream.println("  }")
      }
    }
  }

  /**
   * Emit data structure definitions for types in the given op group
   */
  def emitStructMethods(classes: List[Rep[DSLType]], stream: PrintWriter) {
    def wrapManifest(t: Rep[DSLType]): String = t match {
      case Def(TpeInst(Def(Tpe(name,args,stage)), ps)) if ps != Nil =>
        val tpeArgIndices = ps.map(p => if (isTpePar(p)) 1 else 0).scan(0)(_+_).drop(1).map(_ - 1)
        "makeManifest(classOf[" + name + ps.map(a => "_").mkString("[",",","]") + "], " + (ps.zipWithIndex.map(t => if (isTpePar(t._1)) "m.typeArguments("+tpeArgIndices(t._2)+")" else wrapManifest(t._1))).mkString("List(",",","))")
      case _ =>
        "manifest[" + quote(t) + "]"
    }

    if (classes.length > 0 && classes.exists(c => DataStructs.contains(c))) {
      emitBlockComment("Delite struct", stream, indent=2)
      var structStream = ""
      var first = true
      for (tpe <- classes if DataStructs.contains(tpe) && !FigmentTpes.contains(tpe) && !isMetaType(tpe)) {
        val d = DataStructs(tpe)
				val tpars = tpe.tpePars
        val fields = d.fields.zipWithIndex.map { case ((fieldName,fieldType),i) =>
				("\""+fieldName+"\"", if (isTpePar(fieldType)) "m.typeArguments("+tpars.indexOf(fieldType)+")" else wrapManifest(fieldType)) }
        val prefix = if (first) "    if " else "    else if "
        structStream += prefix + "(m.erasure == classOf["+erasureType(tpe)+"]) Some((classTag(m), collection.immutable.List("+fields.mkString(",")+")))" + nl
        first = false
      }

      if (!first) {
        stream.println("  override def unapplyStructType[T:Manifest]: Option[(StructTag[T], List[(String,Manifest[_])])] = {")
        stream.println("    val m = manifest[T]")
        stream.print(structStream)
        stream.println("    else super.unapplyStructType(m)")
        stream.println("  }")
      }

      var dcDataFieldStream = ""
      var dcSizeFieldStream = ""
      first = true
      for (tpe <- classes if DataStructs.contains(tpe) && ForgeCollections.contains(tpe)) {
        val d = DataStructs(tpe)
        val dc = ForgeCollections(tpe)
        val arrayFields = d.fields.filter(t => getHkTpe(t._2) == MArray)
        if (arrayFields.length != 1 && Config.verbosity > 0) {
          warn("could not infer data field for struct " + tpe.name)
        }
        val sizeField = Impls.get(dc.size).map(i => if (i.isInstanceOf[Getter]) i.asInstanceOf[Getter].field else None)
        if (sizeField.isEmpty && Config.verbosity > 0) {
          warn("could not infer size field for struct " + tpe.name)
        }
        if (arrayFields.length == 1 && sizeField.isDefined) {
          val isTpe = "is"+tpe.name+"Tpe"
          val prefix = if (first) "    if " else "    else if "
          dcDataFieldStream += prefix + "("+isTpe+"(x)) \"" + arrayFields(0)._1 + "\"" + nl
          dcSizeFieldStream += prefix + "("+isTpe+"(x)) \"" + sizeField.get + "\"" + nl
          first = false
        }
      }

      if (!first) {
        stream.println("  override def dc_data_field(x: Manifest[_]) = {")
        stream.print(dcDataFieldStream)
        stream.println("    else super.dc_data_field(x)")
        stream.println("  }")
        stream.println()
        stream.println("  override def dc_size_field(x: Manifest[_]) = {")
        stream.print(dcSizeFieldStream)
        stream.println("    else super.dc_size_field(x)")
        stream.println("  }")
      }
    }
  }

  // TODO: Assumption here is that expressions are simple metadata instantiations
  def emitTypeMetadata(stream: PrintWriter) {
    if (TypeMetadata.nonEmpty) {
      emitBlockComment("Default type metadata", stream, indent=2)
      stream.println("  override def defaultMetadata[A](m: Manifest[A]): List[Metadata] = {")
      var prefix = "    if"
      for ((tpe,data) <- TypeMetadata) {
        stream.print(prefix + "(m.erasure == classOf["+erasureType(tpe)+"]) List(")
        stream.print(data.map(quoteLiteral).mkString(", "))
        stream.println(")")
        prefix = "    else if "
      }
      stream.println("    else Nil")
      stream.println("  } ++ super.defaultMetadata(m)")
    }
  }

  // TODO: This probably won't work for curried method signatures
  def emitTraversalRules(op: Rep[DSLOp], rules: List[DSLRule], stream: PrintWriter, indent: Int, funcSignature: Option[Rep[DSLOp] => String] = None, funcCall: Option[Rep[DSLOp] => String] = None, prefix: String = "") {
    val matchArgs = funcSignature.isDefined
    val patternPrefix = if (matchArgs) "" else makeOpNodeName(op)

    if (matchArgs) emitWithIndent(prefix + funcSignature.get.apply(op) + " = " + makeArgs(op.args) + " match {", stream, indent)

    val innerIndent = if (matchArgs) indent + 2 else indent

    def emitCase(pattern: List[String], rule: Rep[String]) {
      emitWithIndentInline("case " + patternPrefix + pattern.mkString("(",",",")") + " => ", stream, innerIndent)
      val lines = inline(op, rule, quoteLiteral).split(nl)
      if (lines.length > 1) {
        stream.println()
        lines.foreach{line => emitWithIndent(line, stream, innerIndent+2)}
      }
      else
        stream.println(lines.head)
    }

    // Emit all pattern cases (in order) first
    for (r <- rules.flatMap{case r: PatternRule => Some(r) case _ => None}) {
      emitCase(r.pattern, r.rule)
      if (r.commutative) {
        if (r.pattern.length == 2) emitCase(r.pattern.reverse, r.rule)
        else warn("Ignoring commutativity of rewrite rule with more than two arguments")
      }
    }
    if (matchArgs)
      emitWithIndentInline("case _ => ", stream, innerIndent)
    else
      emitWithIndentInline("case rhs@" + makeOpSimpleNodeNameWithArgs(op) + " => ", stream, innerIndent)

    rules.find(r => r.isInstanceOf[SimpleRule]) match {
      case Some(SimpleRule(rule)) =>
        val lines = inline(op, rule, quoteLiteral).split(nl)
        if (lines.length > 1) {
          stream.println()
          lines.foreach{line => emitWithIndent(line, stream, innerIndent+2)}
        }
        else
          stream.println(lines.head)
      case _ if funcCall.isDefined => stream.println("super." + funcCall.get.apply(op))
      case _ =>
    }
    if (matchArgs) {
      emitWithIndent("}", stream, indent)
    }

  }

  /**
   * Emit all rewrite rules (except forwarders) for ops in the given op group
   */
  def emitOpRewrites(opsGrp: DSLOps, stream: PrintWriter) {
    // Get all rewrite rules for ops in this group EXCEPT for forwarding rules (those are generated in Packages)
    val allRewrites = unique(opsGrp.ops).flatMap(o => Rewrites.get(o).map(rules => o -> rules))
    val rewrites = allRewrites.filterNot(rewrite => rewrite._2.exists(_.isInstanceOf[ForwardingRule]))
    if (rewrites.nonEmpty) {
      emitBlockComment("Op rewrites", stream)
      stream.println("trait " + opsGrp.grp.name + "RewriteOpsExp extends " + opsGrp.name + "Exp {")
      stream.println("  this: " + dsl + "Exp => ")
      stream.println()
      rewrites foreach { case (o, rules) =>
        emitTraversalRules(o, rules, stream, 2, Some(makeOpMethodSignature(_,None)), Some(makeOpMethodCall), "override ")
      }
      stream.println("}")
    }
  }


  /**
   * Emit code generators for all codegen nodes in the given op group
   */
  def makeNodeImplicits(o: Rep[DSLOp], xf: String = "f") = {
    o.tpePars.flatMap(t => t.ctxBounds.map(b => makeTpeClsPar(b,t))) ++
    o.implicitArgs.map { a =>
      val argName = opIdentifierPrefix + "." + a.name
      if (isTpeClass(a.tpe)) asTpeClass(a.tpe).signature.wrapper.getOrElse("") + "(" + argName + ")" else argName
    }
  }

  def makeEmitMethodName(o: Rep[DSLOp]) = "emit_" + makeOpMethodName(o)
  def makeEmitMethodCall(o: Rep[DSLOp]) = {
    val args = "sym, " + opIdentifierPrefix
    val implicits = makeNodeImplicits(o)
    val implicitsWithParens = if (implicits.isEmpty) "" else implicits.mkString("(",",",")")
    makeEmitMethodName(o) + "(" + args + ")" + implicitsWithParens
  }
  def makeEmitMethodSignature(o: Rep[DSLOp]) = {
    val implicitArgs = makeOpImplicitArgsWithType(o)
    val args = "sym: Exp[Any], rhs: " + makeOpNodeName(o) + makeTpePars(o.tpePars)
    "def " + makeEmitMethodName(o) + makeTpeParsWithBounds(o.tpePars) + "(" + args + ")" + implicitArgs
  }


  def emitOpCodegen(opsGrp: DSLOps, stream: PrintWriter) {
    val rules = unique(opsGrp.ops).map(o => (o,Impls(o))).filter(_._2.isInstanceOf[CodeGen])
    if (rules.length > 0){
      emitBlockComment("Code generators", stream)
      val save = activeGenerator
      for (g <- generators) {
        activeGenerator = g
        val generatorRules = rules.flatMap{case (o,i) => i.asInstanceOf[CodeGen].decls.collect{case (k,r) if (k == g) => (o,r)}}
        if (generatorRules.length > 0) {
          stream.println("trait " + g.name + "Gen" + opsGrp.name + " extends " + g.name + "GenFat {")
          stream.println("  val IR: " + opsGrp.name + "Exp with " + dsl + "CodegenOps")
          stream.println("  import IR._")
          stream.println()

          // how do we decide whether to add stream.println?
          // hack! (or heuristic, if the glass is half full)
          def shouldInline(s: String) = s.startsWith("@")
          def emitLines(lines: List[String], lineEnd: String = "") = lines.foreach{ line =>
            if (shouldInline(line)) emitWithIndent(line.drop(1), stream, 6)
            else emitWithIndent("stream.println(" + line + " + \"" + lineEnd + "\")", stream, 6)
          }

          // --- Experimental modified version of gen for codegen rules - may not work 100% yet for cpp/cuda
          // Bit of a hack here: Want to get type parameters back but don't need/want to create a method with all
          // arguments and types explicitly stated
          for ((op,r) <- generatorRules) {
            stream.println("  private " + makeEmitMethodSignature(op) + ": Unit = rhs match {")
            stream.println("    case " + opIdentifierPrefix + "@" + makeOpSimpleNodeNameWithArgs(op) + " => ")

            val body = r.decl match {
              case Const(s: String) => s.trim.split(nl).toList.map(_.trim).flatMap{ line =>
                if (shouldInline(line)) List( inline(op, line, quoteLiteral) )
                else quote(line).split(nl).toList
              }
              case x => err("Don't know how to generate codegen rules from type " + x.tp)
            }
            g match {
              case `$cala` =>
                emitWithIndent("stream.println(\"val \"+quote(sym)+\" = {\")", stream, 6)
                emitLines(body)
                emitWithIndent("stream.println(\"}\")", stream, 6)

              case `cuda` | `cpp` =>
                if (op.retTpe == MUnit || op.retTpe == MNothing) {
                  emitLines(body, lineEnd = ";")
                }
                else {
                  if (shouldInline(body.last))
                    err("Last line of cpp / cuda method must be return expression")

                  emitLines(body.take(body.length - 1), lineEnd = ";")
                  emitWithIndent("stream.print(remapWithRef(sym.tp) + \" \" + quote(sym) + \" = \")", stream, 6)
                  emitWithIndent("stream.print(" + body.last + ")", stream, 6)
                  emitWithIndent("stream.println(\";\")", stream, 6)
                }

              case `dot` => emitLines(body)

              case `maxj` => emitLines(body)
              
	      case `chisel` => emitLines(body)

              case _ => err("Unsupported codegen: " + g.toString)
            }
            stream.println("  }")
            stream.println()
          }


          stream.println("  override def emitNode(sym: Sym[Any], rhs: Def[Any]) = rhs match {")
          for ((op,r) <- generatorRules) {
            stream.println("    case " + opIdentifierPrefix + "@" + makeOpSimpleNodeNameWithArgs(op) + " => " + makeEmitMethodCall(op))
          }
          stream.println("    case _ => super.emitNode(sym, rhs)")
          stream.println("  }")
          stream.println("}")
        }
      }
      activeGenerator = save
    }
  }
}
