import dhdl.compiler._
import dhdl.library._
import dhdl.shared._

object DotProductCompiler extends DHDLApplicationCompiler with DotProduct 
object DotProductInterpreter extends DHDLApplicationInterpreter with DotProduct 

trait DotProduct extends DHDLApplication {
	def printUsage = {
    println("Usage: dotprod")
    exit(-1)
	}
  def main() = {
		val a = FixPt(5, 31, 0)
		val b = FixPt(7, 31, 0)
		val r = Reg("a", a)
		assert(r.value==a)
		r.write(b)
		assert(r.value==b)
		assert(r.init==a)
		r.reset
		assert(r.value==a)

		val m = BRAM[Long]("m", 16)
		m.st(3,b)
		assert(m.ld(3)==b)
	}
}