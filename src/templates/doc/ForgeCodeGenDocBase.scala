package ppl.dsl.forge
package templates
package doc

import java.io.{File,PrintWriter,FileWriter}
import scala.tools.nsc.io.{Directory,Path}
import scala.reflect.SourceContext
import scala.collection.mutable.{HashMap,HashSet,ArrayBuffer}
import scala.virtualization.lms.common._
import scala.virtualization.lms.internal.{GenericFatCodegen, GenericCodegen}

import core._
import shared._
import Utilities._

trait ForgeCodeGenDocBase extends ForgeCodeGenBackend with BaseGenOps {
  val IR: ForgeApplicationRunner with ForgeExp
  import IR._

  lazy val targetName = "doc"
  def docDir = buildDir //+ File.separator + targetName + File.separator
  def opsDir: String
  def fileExt: String
  val useReps = true
  def typify(x: Exp[DSLType]): String = if (useReps) repifySome(x) else typifySome(x)

  var docGroups = HashSet[Exp[DSLGroup]]()

  def code(x: String) = x

  var inCodeBlock: Boolean = false
  def inCode(x: => String) = {
    val prevInCode = inCodeBlock
    inCodeBlock = true
    val out = code(x)
    inCodeBlock = prevInCode
    out
  }

  var tableContents = ArrayBuffer[List[String]]()

  def printTable(stream: PrintWriter) {
    tableContents.foreach{row =>
      stream.println(row.mkString(" "))
    }
  }

  def asTable(stream: PrintWriter)(x: => Any) = {
    tableContents = ArrayBuffer[List[String]]()
    x
    printTable(stream)
    tableContents = null
  }
  def tableRow(row: String*) {
    tableContents += row.toList
  }

  def break(stream: PrintWriter) = stream.println()

  def escapeSpecial(x: String): String = x


  override def makeTpePars(args: List[Rep[DSLType]]): String = {
    if (args.length < 1) return ""
    escapeSpecial("[") + args.map(quote).mkString(",") + escapeSpecial("]")
  }

  def typifySome(a: Exp[Any]): String = a match {
    case Def(Arg(name, tpe, default)) => typifySome(tpe)
    case Def(FTpe(args,ret,freq)) => args match {
      case List(Def(Arg(_,`byName`,_))) => " => " + typifySome(ret)
      case _ => "(" + args.map(typifySome).mkString(",") + ") => " + typifySome(ret)
    }
    case Def(Tpe(name, arg, `now`)) => quote(a)
    case Def(Tpe("Var", arg, stage)) => quote(arg(0))
    case Def(TpePar(name, ctx, `now`)) => quote(a)
    case Def(HkTpePar(name, tpePars, ctx, `now`)) => quote(a)
    case Def(TpeInst(Def(Tpe(name, args, `now` | `compile`)), args2)) => name + (if (!args2.isEmpty) escapeSpecial("[") + args2.map(typifySome).mkString(",") + escapeSpecial("]") else "") // is this the right thing to do?
    case Def(TpeInst(Def(Tpe("Var",a1,s1)), a2)) => quote(a2(0))
    case Def(VarArgs(t)) => typifySome(t) + "*"
    case _ => quote(a)
  }

  def fileName(grp: Rep[DSLGroup]) = grp match {
    case Def(TpePar(name,ctx,_)) => "type_" + name.toLowerCase()
    case Def(HkTpePar(name, tpePars, ctx, _)) => "type_" + name.toLowerCase() + "_" + tpePars.map(a => quote(a).toLowerCase()).mkString("_")
    case _ => grp.name.toLowerCase()
  }
  def relFileName(grp: Rep[DSLGroup]) = grp.name //"ops/"+fileName(grp)

  // Regex patterns for parsing op and grp comment blocks
  lazy val NoDocPattern   = "@nodoc".r
  lazy val FieldPattern   = "\\s*@field\\s+(\\w*):\\s*(.*)".r
  lazy val FieldNoDesc    = "\\s*@field\\s+(\\w*)\\s*".r
  lazy val FieldNoName    = "\\s*@field\\s+(.*)".r
  lazy val ParamPattern   = "\\s*@param\\s+(\\w*):\\s*(.*)".r
  lazy val ParamNoDesc    = "\\s*@param\\s+(\\w*)\\s*".r
  lazy val ParamNoName    = "\\s*@param\\s+(.*)".r
  lazy val ReturnPattern  = "\\s*@returns?\\s+(.*)".r
  lazy val OneLineExample = "\\{\\{\\{(.*)\\}\\}\\}".r
  lazy val ExampleStart   = "\\{\\{\\{".r
  lazy val ExampleEnd     = "\\}\\}\\}".r

  // Hack: Numeric, Fractional, and Ordering have no type class info in IR but are actually type classes
  def isDocTypeClass(grp: Rep[DSLGroup]) = isTpeClass(grp) || grp.name == "Numeric" || grp.name == "Fractional" || grp.name == "Ordering"

  def isDataStruct(grp: Rep[DSLGroup]) = grpIsTpe(grp) && DataStructs.contains(grpAsTpe(grp))
  def isPrimitive(grp: Rep[DSLGroup]) = grpIsTpe(grp) && grpAsTpe(grp).stage == future && !isDataStruct(grp)
  def isStaticObject(grp: Rep[DSLGroup]) = {
    if (!OpsGrp.contains(grp)) false
    else {
      val ops = unique(OpsGrp(grp).ops)
      ops.exists(_.style == staticMethod) && !isDataStruct(grp)
    }
  }
  def isPublicGroup(grp: Rep[DSLGroup]) = {
    if (!OpsGrp.contains(grp)) false
    else {
      val ops = unique(OpsGrp(grp).ops)
      ops.exists(_.style != compilerMethod) && !isTpeClassInst(grp) && (!grpIsTpe(grp) || !isForgePrimitiveType(grpAsTpe(grp)))
    }
  }
  def isTypeParameter(grp: Rep[DSLGroup]) = grpIsTpe(grp) && isTpePar(grpAsTpe(grp))

  case class GrpDescription(desc: List[String], fields: List[(String,String)], examples: List[(List[String], String)])
  object GrpDescription {
    def empty(grp: Rep[DSLGroup]) = {
      val grpFields = if (isDataStruct(grp)) DataStructs(grpAsTpe(grp)).fields else Nil
      val fields = grpFields.map(f => (f._1, "")).toList
      new GrpDescription(List("<auto-generated stub>"), fields, Nil)
    }
  }

  case class OpDescription(op: Rep[DSLOp], desc: String, args: List[(String,String)], examples: List[(List[String], String)], returns: Option[String])
  object OpDescription {
    def empty(op: Rep[DSLOp], isTypeClass: Boolean) = new OpDescription(op, "", Nil, Nil, None)
  }

  case class GroupDocumentation(grp: Rep[DSLGroup], header: GrpDescription, ops: List[OpDescription])


  def emitIndex(
    typeclasses: Iterable[GroupDocumentation],
    typeparams: Iterable[GroupDocumentation],
    datastructs: Iterable[GroupDocumentation],
    objects: Iterable[GroupDocumentation],
    primitives: Iterable[GroupDocumentation],
    others: Iterable[GroupDocumentation]
  ): Unit

  def emitGroupHeader(grp: String, desc: GrpDescription, stream: PrintWriter): Unit
  def emitOpFamilyHeader(style: String, stream: PrintWriter): Unit
  def emitOpFamilyFooter(style: String, stream: PrintWriter): Unit
  def makeOpRows(desc: OpDescription, signature: String, stream: PrintWriter): Unit
  def emitGroupFooter(grp: String, stream: PrintWriter): Unit

  def emitDSLImplementation() {
    Directory(Path(docDir)).createDirectory()
    emitGroups()
  }

  def emitGroups() {
    Directory(Path(opsDir)).createDirectory()

    // Have to reorganize methods - don't want to list infix ops under their group if the defining
    // group is not the same type as the first argument to the infix method
    val opMap = HashMap[Exp[DSLGroup], ArrayBuffer[Exp[DSLOp]]]()

    def extractTpe(x: Rep[DSLType]) = x match {
      case Def(Tpe(s,tpePars,stage)) => x
      case Def(TpeInst(t,args)) => t
      case Def(TpePar(name,ctx,s)) => x
      case Def(HkTpePar(name,tpePars,ctx,s)) => x
      case Def(d) => err("did not recognize type " + d)
    }

    for ((grp,opsGrp) <- OpsGrp if !isTpeClassInst(grp)) {
      if (!opMap.contains(grp)) opMap(grp) = ArrayBuffer[Exp[DSLOp]]()
      val ops = unique(opsGrp.ops)

      if (isDocTypeClass(grp))
        opMap(grp) ++= ops
      else {
        val (infixOps, otherOps) = ops.partition(_.style == infixMethod)
        opMap(grp) ++= otherOps
        val (trueInfixOps, otherInfixOps) = infixOps.partition{op => op.args.isEmpty || extractTpe(op.args.head.tpe) == grp }
        opMap(grp) ++= trueInfixOps

        otherInfixOps.foreach{op =>
          val tpe = extractTpe(op.args.head.tpe)
          if (!opMap.contains(tpe)) opMap(tpe) = ArrayBuffer[Exp[DSLOp]]()
          opMap(tpe) += op
        }
      }
    }

    // TODO: This is a bit verbose right now so it can be expanded more easily if needed
    val grps = opMap.keys.groupBy{
      case g if isDocTypeClass(g) => 0
      case g if isTypeParameter(g) => 1
      case g if isDataStruct(g) => 2
      case g if isStaticObject(g) => 3
      case g if isPrimitive(g) => 4
      case g if isPublicGroup(g) => 5 // Ignore type class instances here
      case _ => 6
    }
    val typeclasses = grps.getOrElse(0, Nil).flatMap{grp => createGroupDoc(grp, opMap(grp).toList) }
    val typeparams  = grps.getOrElse(1, Nil).flatMap{grp => createGroupDoc(grp, opMap(grp).toList) }
    val datastructs = grps.getOrElse(2, Nil).flatMap{grp => createGroupDoc(grp, opMap(grp).toList) }
    val objects     = grps.getOrElse(3, Nil).flatMap{grp => createGroupDoc(grp, opMap(grp).toList) }
    val primitives  = grps.getOrElse(4, Nil).flatMap{grp => createGroupDoc(grp, opMap(grp).toList) }
    val others      = grps.getOrElse(5, Nil).flatMap{grp => createGroupDoc(grp, opMap(grp).toList) }

    val allGroups = typeclasses++typeparams++datastructs++objects++primitives++others

    docGroups ++= allGroups.map(_.grp)
    emitIndex(typeclasses, typeparams, datastructs, objects, primitives, others)

    allGroups.foreach{doc => emitGroupDoc(doc)}
  }

  lazy val UnitPattern = "unit\\((\\w+)\\)".r
  // This doesn't work if you give it unit(unit(1)) but that doesn't happen anyway
  private def noUnit(x: String) = UnitPattern.findAllIn(x).fold(x){case (x, t@UnitPattern(v)) => x.replace(t,v) }

  private def argify(a: Exp[DSLArg], name: String): String = a match {
    case Def(Arg(_, tpe, Some(d))) => name + escapeSpecial(": ") + typify(tpe) + " = " + noUnit(escape(d))
    case Def(Arg(_, tpe, None)) => name + escapeSpecial(": ") + typify(tpe)
  }

  private def makeImplicits(tpePars: List[Rep[TypePar]], args: List[Rep[DSLArg]], implicitArgs: List[Rep[DSLArg]]): String = {
    val hkInstantiations = getHkTpeParInstantiations(tpePars, args, implicitArgs)
    val allImplicitArgs = implicitArgs ++ hkInstantiations
    if (allImplicitArgs.length > 0) "(implicit " + allImplicitArgs.zipWithIndex.map{case (t,i) => argify(t,"ev"+i)}.mkString(",") + ")"
    else ""
  }
  private def makeImplicits(o: Rep[DSLOp]): String = {
    // Drop source context - not useful for doc
    val implicits = if (noSourceContextList.contains(o.name) || overrideList.contains(o.name)) o.implicitArgs else o.implicitArgs.drop(1)
    makeImplicits(o.tpePars, o.args, implicits)
  }

  private def makeOpSignature(op: Rep[DSLOp], argNames: List[String], isInfix: Boolean) = {
    val args = if (isInfix) op.args.drop(1) else op.args
    val firstArgs = if (isInfix) op.firstArgs.drop(1) else op.firstArgs

    if (argNames.nonEmpty && argNames.length < args.length) warn("Not all arguments were documented for op " + op.name)

    val names = argNames.map(name => escapeSpecial(name)) ++ args.map(_.name).drop(argNames.length).map(name => escapeSpecial(name))
    val nameMap = HashMap[Rep[DSLArg],String]() ++ args.zip(names)

    def makeArgs(args: List[Rep[DSLArg]]) = args.map(arg => argify(arg, nameMap(arg))).mkString("(", ", ", ")")

    val ret = escapeSpecial(": ") + typify(op.retTpe)
    val implicitArgs = makeImplicits(op)
    val curriedArgs = op.curriedArgs.map(a => makeArgs(a)).mkString("")
    "def " + escapeSpecial(op.name) + makeArgs(firstArgs) + curriedArgs + implicitArgs + ret
  }

  // TODO: Lots of code duplicated between op and grp parsing
  def parseOpDescription(op: Rep[DSLOp], desc: String, isTypeClass: Boolean): Option[OpDescription] = {
    val defaultArgNames = op.args.map(_.name)
    val lines = desc.split(nl).map(_.trim())
    var noDoc: Boolean = false
    var inExample: Boolean = false
    var inDescs: Boolean = false
    val opDesc   = ArrayBuffer[String]()
    val args     = ArrayBuffer[(String,String)]()
    var returns: Option[String] = None
    val examples = ArrayBuffer[List[String]]()
    val exDescs  = ArrayBuffer[List[String]]()

    lines.foreach{
      case ParamPattern(arg, desc) => args += ((arg, desc))
      case ParamNoDesc(arg)        => args += ((arg, ""))
      case ParamNoName(desc)       => args += ((defaultArgNames(args.length), desc))
      case ReturnPattern(line)     => returns = Some(line)

      case OneLineExample(line) =>
        examples += List(line)
        exDescs += Nil
        inDescs = true

      case line =>
        if      (NoDocPattern.findFirstIn(line).isDefined) { noDoc = true }
        else if (ExampleStart.findFirstIn(line).isDefined) { examples += Nil; inExample = true }
        else if (ExampleStart.findFirstIn(line).isDefined) { inExample = false; inDescs = true; exDescs += Nil }
        else {
          if      (inExample) examples(examples.length - 1) = examples.last :+ line
          else if (inDescs)   exDescs(exDescs.length - 1) = exDescs.last :+ line
          else opDesc += line
        }
    }

    val examplesFinal = examples.toList.zip(exDescs.map(_.mkString(" ")).toList)

    if (!noDoc) Some(OpDescription(op, opDesc.mkString(" "), args.toList, examplesFinal, returns)) else None
  }

  def parseGroupDescription(grp: Rep[DSLGroup], desc: String): Option[GrpDescription] = {
    val grpFields = if (isDataStruct(grp)) DataStructs(grpAsTpe(grp)).fields else Nil
    val defaultFieldNames = grpFields.map(_._1)
    val fieldTypes = grpFields.map(_._2)

    val lines = desc.split(nl).map(_.trim())
    var noDoc: Boolean = false
    var inField: Boolean = false
    var inExample: Boolean = false
    var inDescs: Boolean = false
    val grpDesc  = ArrayBuffer[String]()
    val fields   = ArrayBuffer[(String, String)]()
    val examples = ArrayBuffer[List[String]]()
    val exDescs  = ArrayBuffer[List[String]]()

    lines.foreach{
      case FieldPattern(field, desc) => fields += ((field, desc))
      case FieldNoDesc(field) => fields += ((field,""))
      case FieldNoName(desc) => fields += ((defaultFieldNames(fields.length), desc))
      case OneLineExample(line) =>
        examples += List(line)
        exDescs += Nil
        inDescs = true
      case line =>
        if      (NoDocPattern.findFirstIn(line).isDefined) { noDoc = true }
        else if (ExampleStart.findFirstIn(line).isDefined) { examples += Nil; inExample = true }
        else if (ExampleStart.findFirstIn(line).isDefined) { inExample = false; inDescs = true; exDescs += Nil }
        else {
          if      (inExample) examples(examples.length - 1) = examples.last :+ line
          else if (inDescs)   exDescs(exDescs.length - 1) = exDescs.last :+ line
          else grpDesc += line
        }
    }
    val examplesFinal = examples.toList.zip(exDescs.map(_.mkString(" ")).toList)
    if (!noDoc) Some(GrpDescription(grpDesc.map(_.trim()).toList, fields.toList, examplesFinal)) else None
  }

  def createGroupDoc(grp: Rep[DSLGroup], ops: List[Rep[DSLOp]]): Option[GroupDocumentation] = {
    val grpDesc = GrpDoc.get(grp) match {
      case Some(desc) => parseGroupDescription(grp, desc)
      case None => Some(GrpDescription.empty(grp))
    }
    val opDescs = ops.flatMap{op =>
      OpDoc.get(op) match {
        case Some(desc) => parseOpDescription(op, desc, isDocTypeClass(grp))
        case _ if op.name.startsWith("forge_") => None
        case None => Some( OpDescription.empty(op, isDocTypeClass(grp)) )
      }
    }
    if (grpDesc.isDefined && opDescs.nonEmpty) Some(GroupDocumentation(grp, grpDesc.get, opDescs)) else None
  }


  def emitOpFamily(style: String, grp: Rep[DSLGroup], ops: List[OpDescription], stream: PrintWriter) {
    if (ops.nonEmpty) {
      emitOpFamilyHeader(style, stream)
      //asTable(stream) {
      ops.zipWithIndex.foreach{case (desc,i) =>
        if (i > 0) break(stream)
        val signature = inCode{ makeOpSignature(desc.op, desc.args.map(_._1), desc.op.style == infixMethod && !isDocTypeClass(grp)) }
        makeOpRows(desc, signature, stream)
      }
      //}
      emitOpFamilyFooter(style, stream)
    }
  }

  def emitGroupDoc(doc: GroupDocumentation) {
    val grp = doc.grp
    val grpDesc = doc.header
    val opDescs = doc.ops
    val stream = new PrintWriter(new FileWriter(opsDir+File.separator+fileName(grp)+"."+fileExt))

    emitGroupHeader(grp.name, grpDesc, stream)

    val directOps = opDescs.filter(_.op.style == directMethod)
    val staticOps = opDescs.filter(_.op.style == staticMethod)
    val infixOps  = opDescs.filter(_.op.style == infixMethod)
    val implicitOps = opDescs.filter(_.op.style == implicitMethod)

    if (staticOps.nonEmpty) emitOpFamily("Static methods", grp, staticOps, stream)
    if (infixOps.nonEmpty) emitOpFamily("Infix methods", grp, infixOps, stream)
    if (implicitOps.nonEmpty) emitOpFamily("Implicit methods", grp, implicitOps, stream)
    if (directOps.nonEmpty) emitOpFamily("Related methods", grp, directOps, stream)
    emitGroupFooter(grp.name, stream)

    stream.close()
  }

}