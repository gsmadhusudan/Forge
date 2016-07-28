import dhdl.compiler._
import dhdl.library._
import dhdl.shared._

object DotProductCompiler extends DHDLApplicationCompiler with DotProduct
object DotProductInterpreter extends DHDLApplicationInterpreter with DotProduct
trait DotProduct extends DHDLApplication {
  type T = SInt
  type Array[T] = ForgeArray[T]

  def dotproduct(a: Rep[Array[T]], b: Rep[Array[T]]) = {
    val tileSize = param(4); domainOf(tileSize) = (96, 19200, 96)
    val outerPar = param(1); domainOf(outerPar) = (1, 6, 1)
    val innerPar = param(1); domainOf(innerPar) = (1, 192, 1)
    val N = a.length; bound(N) = 187200000

    val v1 = OffChipMem[T](N)
    val v2 = OffChipMem[T](N)
    val dataSize = ArgIn[SInt]
    val out = ArgOut[T]

    setMem(v1, a)
    setMem(v2, b)
    setArg(dataSize, N)

    Accel {
      Fold(dataSize by tileSize par outerPar)(out, 0.as[T]){ i =>
        val b1 = FIFO[T](512)
        val b2 = FIFO[T](512)
        Parallel {
          b1 := v1(i::i+tileSize)
          b2 := v2(i::i+tileSize)
        }
        Reduce(tileSize par innerPar)(0.as[T]){ii =>
          b1.pop() * b2.pop()
        }{_+_}
      }{_+_}
    }
    getArg(out)
  }

  def main() {
    val N = args(0).to[SInt]
    val a = Array.fill(N)(random[T](10))
    val b = Array.fill(N)(random[T](10))

    println("a: " + a.mkString(", "))
    println("b: " + b.mkString(", "))

    val result = dotproduct(a, b)
    val gold = a.zip(b){_*_}.reduce{_+_}
    println("expected: " + gold.mkString)
    println("result:   " + result.mkString)
    assert(result == gold)
  }
}
