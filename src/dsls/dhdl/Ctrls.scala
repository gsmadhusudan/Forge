package ppl.dsl.forge
package dsls
package dhdl

trait CtrlOps {
	this: DHDLDSL =>

	def importCtrls () = {

		val Ctr = tpe("Ctr")
		data (Ctr, ("_name", MString), ("_min", MInt), ("_max", MInt), ("_step", MInt), ("_val", MInt))
		static (Ctr) ("apply", Nil, MethodSignature(List(("name", MString, "unit(\"\")"),
			                                               ("min", MInt, "unit(0)"), 
																										 MInt, MInt),
																								Ctr), effect=mutable) implements allocates(Ctr,
			${$name}, ${$min}, ${$2}, ${$3}, ${ unit(0) })
		val CtrOps = withTpe(Ctr)
		CtrOps {
			infix ("mkString") (Nil :: MString) implements composite ${
				unit("ctr(") +
        unit("name:") + $self.name +
			  unit(", min:") + $self.min +
			  unit(", max:") + $self.max +
			  unit(", step:") + $self.step + 
				unit(")")
      }
			infix ("name") (Nil :: MString) implements getter(0, "_name")
			infix ("min") (Nil :: MInt) implements getter(0, "_min")
			infix ("max") (Nil :: MInt) implements getter(0, "_max")
			infix ("step") (Nil :: MInt) implements getter(0, "_step")
		}

		val CtrChain = tpe("CtrChain")
		data (CtrChain, ("_chain", MArray(Ctr)))
    compiler (CtrChain) ("ctrchain_from_array", Nil, MArray(Ctr) :: CtrChain,effect=mutable) implements allocates(CtrChain, ${$0})
		static (CtrChain) ("apply", Nil, varArgs(Ctr) :: CtrChain) implements composite ${
      val array = array_empty[Ctr](unit($0.length))
      val ctrchain = ctrchain_from_array(array)
      for (i <- 0 until $0.length) { ctrchain(i) = $0.apply(i) }
			ctrchain.unsafeImmutable
    }
		val CtrChainOps = withTpe(CtrChain)
		CtrChainOps {
			infix ("mkString") (Nil :: MString) implements composite ${
				unit("ctrchain[") +
				array_mkstring[String](
					array_map[Ctr,String]($self.chain, c => c.mkString), ",") + 
				unit("]")
			}
			infix ("chain") (Nil :: MArray(Ctr)) implements getter(0, "_chain")
      infix ("update") ((MInt,Ctr) :: MUnit, effect = write(0)) implements composite ${ array_update($0.chain, $1, $2) }
			infix ("length") (Nil :: MInt) implements composite ${ $self.chain.length }
		}

		val Pipe = tpe("Pipe")
		data (Pipe, ("_ctrs", CtrChain)) //TODO: Modify pipe to keep track of nodes inside
		static (Pipe) ("apply", Nil, CtrChain :: Pipe) implements allocates(Pipe, ${$0})

		val loop = compiler (Pipe) ("loop", Nil, (("ctr", Ctr), ("lambda", MInt ==> MUnit)) :: MUnit)
		impl (loop) (composite ${
			var i = $ctr.min
			while (i < $ctr.max) {
				$lambda (i)
				i = i + $ctr.step
			}
		})

		val pipe_map1 = direct (Pipe) ("map1", Nil, (("ctrs", CtrChain), ("func", varArgs(MInt) ==> MUnit)) :: Pipe) 
		impl (pipe_map1) (composite ${
			val pipe = Pipe( $ctrs )
			def ctr(i:Int):Rep[Ctr] = $ctrs.chain.apply(unit(i))
			loop(ctr(0), (i:Rep[Int]) => $func(Seq(i))) 
			pipe
		})

		val pipe_map2 = direct (Pipe) ("map2", Nil, (("ctrs", CtrChain), ("func", varArgs(MInt) ==> MUnit)) :: Pipe) 
		impl (pipe_map2) (composite ${
			val pipe = Pipe( $ctrs )
			def ctr(i:Int):Rep[Ctr] = $ctrs.chain.apply(unit(i))
			loop(ctr(0), (i:Rep[Int]) => 
					loop(ctr(1), (j:Rep[Int]) => $func(Seq(i, j))) 
			)
			pipe
		})

		val pipe_map3 = direct (Pipe) ("map3", Nil, (("ctrs", CtrChain), ("func", varArgs(MInt) ==> MUnit)) :: Pipe) 
		impl (pipe_map3) (composite ${
			val pipe = Pipe( $ctrs )
			def ctr(i:Int):Rep[Ctr] = $ctrs.chain.apply(unit(i))
			loop(ctr(0), (i:Rep[Int]) => 
				loop(ctr(1), (j:Rep[Int]) => 
					loop(ctr(2), (k:Rep[Int]) => $func(Seq(i, j, k))) 
				)
			)
			pipe
		})

		//TODO: Won't work here until have metadata
		val pipe_map = direct (Pipe) ("map", Nil, (("ctrs", CtrChain), ("func", varArgs(MInt) ==> MUnit)) :: Pipe) 
		impl (pipe_map) (composite ${

			def prepend(x:Rep[Int], seq: Seq[Rep[Int]]) = {
				Seq.tabulate[Rep[Int]](seq.length+1) ((i:Int) => 
			 		if (i==0) x else seq(i-1) 
				)
			}
			def recPipe (idx:Rep[Int], idxs:Seq[Rep[Int]]): Rep[Unit] = {
				val ctr = $ctrs.chain.apply(idx)
				if (idx == unit(1)) {
					loop(ctr, ( (i:Rep[Int]) => $func( prepend(i, idxs) )) )
				} else {
					loop(ctr, ( (i:Rep[Int]) => recPipe(idx - unit(1), prepend(i, idxs) ) ))
				}
			}
			val pipe = Pipe( $ctrs )

			recPipe( $ctrs.length, Seq.empty[Rep[Int]] )

			pipe
		})

		val T = tpePar("T")
		val Reg = lookupTpe("Reg")
		val pipe_reduce1 = direct (Pipe) ("reduce1", T, (("ctrs", CtrChain), ("accum", Reg(T)),
			("reduceFuc", (T, T) ==> T) , ("mapFunc", varArgs(MInt) ==> T)) :: Pipe) 
		impl (pipe_reduce1) (composite ${
			val pipe = Pipe( $ctrs )
			def ctr(i:Int):Rep[Ctr] = $ctrs.chain.apply(unit(i))
			def func(idxs:Seq[Rep[Int]]) = $accum.write($reduceFuc($accum.value, $mapFunc(idxs)))
			loop(ctr(0), (i:Rep[Int]) => func(Seq(i))) 
			pipe
		})
		val pipe_reduce2 = direct (Pipe) ("reduce2", T, (("ctrs", CtrChain), ("accum", Reg(T)),
			("reduceFuc", (T, T) ==> T) , ("mapFunc", varArgs(MInt) ==> T)) :: Pipe) 
		impl (pipe_reduce2) (composite ${
			val pipe = Pipe( $ctrs )
			def ctr(i:Int):Rep[Ctr] = $ctrs.chain.apply(unit(i))
			def func(idxs:Seq[Rep[Int]]) = $accum.write($reduceFuc($accum.value, $mapFunc(idxs)))
			loop(ctr(0), (i:Rep[Int]) => 
					loop(ctr(1), (j:Rep[Int]) => func(Seq(i, j))) 
			)
			pipe
		})
		//val pipe_reduce3 = direct (Pipe) ("reduce3", T, (("ctrs", CtrChain), ("accum", Reg(T)), ("func",
		//	(Reg(T), varArgs(MInt)) ==> MUnit)) :: Pipe) 
		//impl (pipe_reduce3) (composite ${
		//	val pipe = Pipe( $ctrs )
		//	def ctr(i:Int):Rep[Ctr] = $ctrs.chain.apply(unit(i))
		//	val funca = $func($accum, _:Seq[Rep[Int]])
		//	loop(ctr(0), (i:Rep[Int]) => 
		//		loop(ctr(1), (j:Rep[Int]) => 
		//			loop(ctr(2), (k:Rep[Int]) => funca(Seq(i, j, k))) 
		//		)
		//	)
		//	pipe
		//})

	}
}