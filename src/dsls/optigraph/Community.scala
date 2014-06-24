/*//////////////////////////////////////////////////////////////
Author: Christopher R. Aberger

Description: Stores data asscoicated with nodes in an array 
buffer indexed by internal node IDs
*///////////////////////////////////////////////////////////////

package ppl.dsl.forge
package dsls 
package optigraph

import core.{ForgeApplication,ForgeApplicationRunner}

trait CommunityOps {
  this: OptiGraphDSL =>
  def importCommunityOps() {
    val UndirectedGraph = lookupTpe("UndirectedGraph")
    val NodeData = lookupTpe("NodeData")
    val Node = lookupTpe("Node")
    val NodeIdView = lookupTpe("NodeIdView")
    val Tuple2 = lookupTpe("Tup2")
    val Tuple3 = lookupTpe("Tup3")
    val Tuple5 = lookupTpe("Tup5")
    val Community = tpe("Community")
    val T = tpePar("T")
    val K = tpePar("K")
    val V = tpePar("V")
    val SHashMap = tpe("scala.collection.mutable.HashMap", (K,V))

    data(Community,("_size",MInt),("_modularity",MDouble),("_canImprove",MBoolean),("_totalWeight",MDouble),("_graph",UndirectedGraph),("_n2c",MArray(MInt)),("_tot",MArray(MDouble)),("_in",MArray(MDouble)))
    static(Community)("apply", Nil, ("g",UndirectedGraph) :: Community, effect = mutable) implements allocates(Community,${alloc_size(g)},${unit(0d)},${unit(true)},${alloc_total_weight($0)},${$0},${alloc_ints(alloc_size(g),{e => e})},${alloc_weights(g)},${alloc_selfs(g)})
    static(Community)("apply", Nil, MethodSignature(List(("size",MInt),("modularity",MDouble),("canImprove",MBoolean),("totalWeight",MDouble),("g",UndirectedGraph),("n2c",MArray(MInt)),("tot",MArray(MDouble)),("in",MArray(MDouble))),Community),effect= mutable) implements allocates(Community,${size},${modularity},${canImprove},${totalWeight},${g},${n2c},${tot},${in})

    //FIXME
    //lots of errors removed but wrong answer when effect = mutable is taken out

    /*
      n2c -> size == numNodes, indexed by nodeID gives the community a node belongs to
      in,tot -> size == # of communities, used for modularity calculation
        tot == total weighted degree of the community
        in == sum of degree of links strictly within the community (divided by 2)
    */

    val CommunityOps = withTpe(Community)
    CommunityOps{  
      infix("modularity")(Nil :: MDouble) implements composite ${
        val g = $self.graph
        val tot = $self.tot
        val in = $self.in
        var m2 = $self.totalWeight
        
        g.sumOverNodes({ n => 
          if(tot(n.id) > 0)
            (in(n.id)/m2) - (tot(n.id)/m2)*(tot(n.id)/m2)
          else 
            0d
        })
      }
      infix("modularity")( (("in",MArray(MDouble)),("tot",MArray(MDouble))) :: MDouble) implements composite ${
        val g = $self.graph
        var m2 = $self.totalWeight
        
        g.sumOverNodes({ n => 
          if(tot(n.id) > 0)
            (in(n.id)/m2) - (tot(n.id)/m2)*(tot(n.id)/m2)
          else 
            0d
        })
      }
      infix("modularityGain")( (("totc",MDouble),("dnodecomm",MDouble),("w_degree",MDouble)) :: MDouble) implements composite ${
  
        val degc = w_degree
        val m2 = $self.totalWeight  //total weight is really a function of the graph not the comm.
        val dnc = dnodecomm 

        (dnc - totc*degc/m2)
      }
      infix("display")(Nil :: MUnit, effect=simple) implements single ${
        var i = 0
        while(i < $self.size){
          println(" " + i + "/" + $self.n2c(i) + "/" + $self.in(i) + "/" + $self.tot(i))
          i += 1
        }
      }
      infix("display")((("n2c",MArray(MInt)),("in",MArray(MDouble)),("tot",MArray(MDouble))) :: MUnit, effect=simple) implements single ${
        var i = 0
        while(i < $self.size){
          println(" " + i + "/" + n2c(i) + "/" + in(i) + "/" + tot(i))
          i += 1
        }
      }
      infix("generateNewGraph")(Nil :: UndirectedGraph) implements composite ${
        val g = $self.graph
        val tot = $self.tot
        val in = $self.in
        val n2c = $self.n2c
        val size = $self.size

        val originalNodeIds = NodeData.fromFunction(size,i=>i)
        val groupedComms = originalNodeIds.groupBy(k => n2c(k),v => v)

        val newSize = fhashmap_size(groupedComms)
        val newComms = NodeData.fromFunction(newSize,i => i)
        val oldComms = NodeData(fhashmap_keys(groupedComms))
        
        val old2newHash = fhashmap_from_arrays[Int,Int](oldComms.getRawArray,newComms.getRawArray)

        //For each edge
          //find src and dst comm (use n2c)
          //if edge between comm exists
            //add edge with weight
          //else
            //add to edge with weight

        //Should be safe parallelism here.
        val newGraph = newComms.map({ src =>
          //println("src: " + src)
          val oldComm = oldComms(src)
          val nodeHash = SHashMap[Int,Double]()

          val nodesInComm = NodeData(fhashmap_get(groupedComms,oldComm))
          nodesInComm.foreach({ n => 
            val (nbrs,nbrWeights) = unpack(g.getNeighborsAndWeights(Node(n)))
            var i = 0

            //println("len: " + nbrs.length)
            while(i < nbrs.length){
              val dst = old2newHash(n2c(nbrs(i)))
              //println("dst: " + dst)
              if(nodeHash.contains(dst)){
                nodeHash.update(dst,nodeHash(dst)+nbrWeights(i))
              }
              else{
                nodeHash.update(dst,nbrWeights(i))
              }
              i += 1
            }
          })
          nodeHash
        })
        val numEdges = newGraph.mapreduce[Int](a => array_length(a.keys), (a,b) => a+b, a => true)
        val serial_out = $self.assignUndirectedIndicies(newSize,numEdges,newGraph.getRawArray)

        UndirectedGraph(newSize,oldComms.getRawArray,serial_out._1,serial_out._2,serial_out._3)    
      }

      infix("assignUndirectedIndicies")((("numNodes",MInt),("numEdges",MInt),("src_groups",MArray(SHashMap(MInt,MDouble)))) :: Tuple3(MArray(MInt),MArray(MInt),MArray(MDouble))) implements single ${
        val src_edge_array = NodeData[Int](numEdges)
        val src_edge_weight_array = NodeData[Double](numEdges)
        val src_node_array = NodeData[Int](numNodes)
        var i = 0
        var j = 0
        //I can do -1 here because I am pruning so the last node will never have any neighbors
        while(i < numNodes){
          val neighborhash = src_groups(i)
          val neighborhood = NodeData(neighborhash.keys).sort
          val neighWeights = neighborhood.map(e => neighborhash(e))
          var k = 0
          while(k < neighborhood.length){
            src_edge_array(j) = neighborhood(k)
            src_edge_weight_array(j) = neighWeights(k)
            j += 1
            k += 1
          }
          if(i < numNodes-1){
            src_node_array(i+1) = neighborhood.length + src_node_array(i)
          }
          i += 1
        }
        pack(src_node_array.getRawArray,src_edge_array.getRawArray,src_edge_weight_array.getRawArray)
      }
      //Why do I have to mark the community as mutable still?
      infix("buildNeighboringCommunities")((("n",Node),("n2c",MArray(MInt))) :: Tuple2(MArray(MInt),MArray(MDouble))) implements single ${
        //////////////////////////////////////////
        //Formerly neigh communities method
        //This section pulls all the communities out of the neighborhoods
        //and sums inter-weights for the neighborhood per community.
        val g = $self.graph
        val (nbrs,nbrWeights) = unpack(g.getNeighborsAndWeights(n))
        val neighPos = array_empty[Int](nbrs.length+1) //holds indexes in for comm weights (neighbors plus current node)
        //FIXME relies on fact that array_empty gives and array of 0's
        val commWeights = array_empty[Double]($self.size) //holds comm weights for neighborhoods            neighPos(0) = node_comm

        var i = 0
        //println("nbr len: " + nbrs.length)
        while(i < nbrs.length){
          val neighComm = n2c(nbrs(i)) //READ
          neighPos(i+1) = neighComm
          commWeights(neighComm) = commWeights(neighComm)+nbrWeights(i)
          i += 1
        }
        /////////////////////////////////////////
        pack(neighPos,commWeights)
      }
      infix("findBestCommunityMove")((("n",Node),("n2c",MArray(MInt)),("tot",MArray(MDouble)),("neighPos",MArray(MInt)),("commWeights",MArray(MDouble))) :: Tuple2(MInt,MDouble)) implements single ${
        val g = $self.graph
        val node_comm = n2c(n.id) //READ
        val w_degree = g.weightedDegree(n.id)
        
        //By default set everything to our current community
        var best_comm = node_comm
        var best_nblinks = commWeights(node_comm)
        var best_increase = $self.modularityGain(tot(node_comm)-g.weightedDegree(n.id),commWeights(node_comm),w_degree) //READ

        //println("number of comms: " + array_length(neighPos))
        var i = 1 //we already did our own node above
        //println()
        //println("Node: " + n.id)
        while(i < array_length(neighPos)){
          //println("comm: " + neighPos(i) + " weight: " + commWeights(neighPos(i)))
          val neigh_comm = neighPos(i)
          val weight = commWeights(neigh_comm)
          
          val increase = $self.modularityGain(tot(neigh_comm),weight,w_degree) //READ
          if(increase > best_increase){
            best_comm = neigh_comm
            best_nblinks = weight
            best_increase = increase
          }
          i += 1            
        }
        pack(best_comm,best_nblinks) 
      }
      infix("parallelArrayCopy")((("dst",MArray(T)),("src",MArray(T))) :: MUnit, effect=write(1),aliasHint = copies(2), addTpePars = T ) implements composite ${
        NodeIdView(array_length(src)).foreach({i =>
          dst(i) = src(i)
        })
      }
      infix("louvain")(Nil :: Community) implements composite ${
        val g = $self.graph
        val totalWeight = $self.totalWeight
        val size = $self.size 

        val tot = array_empty[Double](array_length($self.tot)) 
        val in = array_empty[Double](array_length($self.in)) 
        val n2c = array_empty[Int](array_length($self.n2c)) 

        $self.parallelArrayCopy(tot,$self.tot)  //tot = $self.tot
        $self.parallelArrayCopy(in,$self.in) //in = $self.in
        $self.parallelArrayCopy(n2c,$self.n2c) //n2c = $self.n2c

        /*
        val oldtot = array_empty[Double](array_length($self.tot)) 
        val oldin = array_empty[Double](array_length($self.in)) 
        val oldn2c = array_empty[Int](array_length($self.n2c)) 
        $self.parallelArrayCopy(oldn2c,$self.n2c) //oldn2c = $self.n2c
        $self.parallelArrayCopy(oldin,$self.in) //oldin = $self.in
        $self.parallelArrayCopy(oldtot,$self.tot) //oldtot = $self.tot
        */

        val min_modularity = 0.000001
        var nb_moves = 0
        var nb_pass_done = 0
        var new_mod = $self.modularity
        var cur_mod = new_mod

        var continue = true
        while(continue){
          cur_mod = new_mod
          nb_moves = 0
          nb_pass_done += 1

          //Makes this effectively for each community
          g.foreachNode{ n =>
            val node_comm = n2c(n.id) //READ
            val (neighPos,commWeights) = unpack($self.buildNeighboringCommunities(n,n2c))
            val (best_comm,best_nblinks) = unpack($self.findBestCommunityMove(n,n2c,tot,neighPos,commWeights))

            if(best_comm != node_comm){
              //println("moving to: " + best_comm)
              /////////////////////////////////////////////
              ////REALLY Needs to be atomic
              ////MUTATION
              $self.insert(n2c,in,tot,n.id,node_comm,commWeights(node_comm),best_comm,best_nblinks) //WRITE
              /////////////////////////////////////////////
            }
          }//end parallel section
          
          /////////////////////////////////////////
          //Copy new values over for next iteration
          /*
          $self.parallelArrayCopy(oldtot,tot) //oldtot = tot
          $self.parallelArrayCopy(oldin,in) //oldin = in
          $self.parallelArrayCopy(oldn2c,n2c) //oldn2c = n2c
          */
          //////////////////////////////////////////

          new_mod = $self.modularity(in,tot)
          continue = (new_mod-cur_mod) > min_modularity
        }
        val improvement = (nb_pass_done > 1) || (new_mod != cur_mod)
        println("Number of passes: " + nb_pass_done + " improvement: " + improvement)
        Community(size,new_mod,improvement,totalWeight,g,n2c,tot,in)
      }

      infix("insert")( MethodSignature(List(("n2c",MArray(MInt)),("in",MArray(MDouble)),("tot",MArray(MDouble)),("node",MInt),("old_comm",MInt),("olddnodecomm",MDouble),("comm",MInt),("dnodecomm",MDouble)),MUnit), effect = write(1,2,3)) implements single ${
        array_update(tot,old_comm,tot(old_comm)-$self.graph.weightedDegree(node))
        array_update(tot,comm,tot(comm)+$self.graph.weightedDegree(node))

        array_update(in,old_comm,in(old_comm)-(2*olddnodecomm+$self.graph.numSelfLoops(node)))
        array_update(in,comm,in(comm)+(2*dnodecomm+$self.graph.numSelfLoops(node)))

        array_update(n2c,node,comm)
      }

      infix ("size") (Nil :: MInt) implements getter(0, "_size")
      infix ("totalWeight") (Nil :: MDouble) implements getter(0, "_totalWeight")
      infix ("storedModularity") (Nil :: MDouble) implements getter(0, "_modularity")
      infix ("canImprove") (Nil :: MBoolean) implements getter(0, "_canImprove")
      infix ("graph") (Nil :: UndirectedGraph) implements getter(0, "_graph")
      infix ("n2c") (Nil :: MArray(MInt)) implements getter(0, "_n2c")
      infix ("in") (Nil :: MArray(MDouble)) implements getter(0, "_in")
      infix ("tot") (Nil :: MArray(MDouble)) implements getter(0, "_tot")   
      
      infix ("tot") (MInt :: MDouble) implements composite ${array_apply($self.tot, $1)}
      infix ("in") (MInt :: MDouble) implements composite ${array_apply($self.in, $1)}
      infix ("n2c") (MInt :: MInt) implements composite ${array_apply($self.n2c, $1)}

      infix ("updateTot") ( (MInt,MDouble) :: MUnit, effect = write(0)) implements composite ${ array_update($self.tot,$1,$2)}
      infix ("updateIn") ( (MInt,MDouble) :: MUnit, effect = write(0)) implements composite ${ array_update($self.in,$1,$2)}
      infix ("updateN2c") ( (MInt,MInt) :: MUnit, effect = write(0)) implements composite ${ array_update($self.n2c,$1,$2)}

      infix ("setTot") (MArray(MDouble) :: MUnit, effect = write(0)) implements setter(0, "_tot", quotedArg(1))
      infix ("setIn") (MArray(MDouble) :: MUnit, effect = write(0)) implements setter(0, "_in", quotedArg(1))
      infix ("setN2c") (MArray(MInt) :: MUnit, effect = write(0)) implements setter(0, "_n2c", quotedArg(1))
    }
    compiler (Community) ("alloc_total_weight", Nil, UndirectedGraph :: MDouble) implements single ${$0.totalWeight}
    compiler (Community) ("alloc_size", Nil, UndirectedGraph :: MInt) implements single ${$0.numNodes}
    compiler (Community) ("alloc_doubles", Nil, (MInt,(MInt ==> MDouble)) :: MArray(MDouble)) implements single ${array_fromfunction[Double]($0,$1)}
    compiler (Community) ("alloc_ints", Nil, (MInt,(MInt ==> MInt)) :: MArray(MInt)) implements single ${array_fromfunction[Int]($0,$1)}
    compiler (Community) ("alloc_weights", Nil, UndirectedGraph :: MArray(MDouble)) implements single ${array_fromfunction[Double](alloc_size($0),{n => $0.weightedDegree(n)})}
    compiler (Community) ("alloc_selfs", Nil, UndirectedGraph :: MArray(MDouble)) implements single ${array_fromfunction[Double](alloc_size($0),{n => $0.numSelfLoops(n)})}

  } 
}