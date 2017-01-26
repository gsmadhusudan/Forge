package optiql.shared.ops

import scala.virtualization.lms.common.Base
import optiql.shared._
import optiql.shared.ops._
import org.scala_lang.virtualized.{RefinedManifest,SourceContext}

//TODO: this trait is basically a misc. grab bag of features, but most of it should be pushed directly into Forge
trait RewriteOps extends Base {
  this: OptiQL => 

  implicit class TableOpsCls[A:Manifest](val self: Rep[Table[A]])(implicit pos: SourceContext) {
    def printAsTable(maxRows: Rep[Int] = unit(100))(implicit __pos: SourceContext) = table_printastable[A](self,maxRows)(implicitly[Manifest[A]],__pos)
    def writeAsJSON(path: Rep[String])(implicit __pos: SourceContext) = table_writeasjson[A](self,path)(implicitly[Manifest[A]],__pos)
    //def writeAsCSV
  }

  def table_printastable[A:Manifest](self: Rep[Table[A]],maxRows: Rep[Int] = unit(100))(implicit __pos: SourceContext): Rep[Unit]
  def table_writeasjson[A:Manifest](self: Rep[Table[A]],path: Rep[String])(implicit __pos: SourceContext): Rep[Unit]
}

trait RewriteCompilerOps extends RewriteOps {
  this: OptiQL =>

  def groupByHackImpl[K:Manifest,V:Manifest](self: Rep[Table[V]], keySelector: Rep[V] => Rep[K])(implicit pos: SourceContext): Rep[Table[Tup2[K,Table[V]]]]

  def sortHackImpl[A:Manifest](self: Rep[Table[A]], comparator: (Rep[A],Rep[A]) => Rep[Int])(implicit pos: SourceContext): Rep[Table[A]]

  def compareHackImpl[A:Manifest:Ordering](lhs: Rep[A], rhs: Rep[A]): Rep[Int]

  def zeroType[T:Manifest]: Rep[T] = (manifest[T] match { //need a more robust solution, e.g. type class
    //case StructType(tag,elems) => struct[T](tag, elems.map(e => (e._1, zeroType(e._2))))
    case v if v == manifest[Int] => unit(0)
    case v if v == manifest[Long] => unit(0L)
    case v if v == manifest[Double] => unit(0.0)
    case v if v == manifest[Float] => unit(0.0f)
    case _ => cast_asinstanceof[Null,T](unit(null))
  }).asInstanceOf[Rep[T]]

  def minValue[T:Manifest]: Rep[T] = (manifest[T] match {
    case v if v == manifest[Int] => unit(scala.Int.MinValue)
    case v if v == manifest[Long] => unit(scala.Long.MinValue)
    case v if v == manifest[Double] => unit(scala.Double.MinValue)
    case v if v == manifest[Float] => unit(scala.Float.MinValue)
    case v if v == manifest[Char] => unit(scala.Char.MinValue)
    case _ => cast_asinstanceof[Null,T](unit(null)) //shouldn't be used for reference types
  }).asInstanceOf[Rep[T]]

  def maxValue[T:Manifest]: Rep[T] = (manifest[T] match {
    case v if v == manifest[Int] => unit(scala.Int.MaxValue)
    case v if v == manifest[Long] => unit(scala.Long.MaxValue)
    case v if v == manifest[Double] => unit(scala.Double.MaxValue)
    case v if v == manifest[Float] => unit(scala.Float.MaxValue)
    case v if v == manifest[Char] => unit(scala.Char.MaxValue)
    case _ => cast_asinstanceof[Null,T](unit(null)) //shouldn't be used for reference types
  }).asInstanceOf[Rep[T]]

  def upgradeInt[T:Manifest](value: Rep[Int]): Rep[T] = (manifest[T] match {
    case v if v == manifest[Int] => value
    case v if v == manifest[Long] => value.toLong
    case v if v == manifest[Double] => value.toDouble
    case v if v == manifest[Float] => value.toFloat
    case _ => throw new RuntimeException("ERROR: don't know how to average type " + manifest[T].toString)
  }).asInstanceOf[Rep[T]]

  def createRecord[T:Manifest](record: Rep[ForgeArray[String]]): Rep[T] = {
    val (elems, isRecord) = manifest[T] match {
      case rm: RefinedManifest[T] => (rm.fields, true)
      case m => (List(("",m)), false)
    }

    val fields = Range(0,elems.length) map { i =>
      val (field, tp) = elems(i)
      tp.toString match {
        case s if s.contains("String") => (field, record(unit(i)))
        case d if d.contains("Date")   => (field, Date(record(unit(i))))
        case "Double"  => (field, record(unit(i)).toDouble)
        case "Float"   => (field, record(unit(i)).toFloat)
        case "Boolean" => (field, record(unit(i)) == "true")
        case "Int"     => (field, record(unit(i)).toInt)
        case "Long"    => (field, record(unit(i)).toLong)
        case "Char"    => (field, fstring_fcharat(record(unit(i)), unit(0)))
        case _ => throw new RuntimeException("Don't know hot to automatically parse type " + tp.toString + ". Try passing in your own parsing function instead.")
      }
    }
    if (isRecord) record_new[T](fields.asInstanceOf[Seq[(String,Rep[_])]]:_*)(manifest[T].asInstanceOf[RefinedManifest[T]]) //.asInstanceOf[Seq[(String, Rep[_])]])
    else fields(0)._2.asInstanceOf[Rep[T]]
  }
}
