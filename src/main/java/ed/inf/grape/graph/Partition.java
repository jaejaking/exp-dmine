package ed.inf.grape.graph;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.Vector;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jgrapht.graph.DefaultEdge;
import org.roaringbitmap.RoaringBitmap;

import ed.inf.discovery.Pattern;
import ed.inf.discovery.auxiliary.FreqEdge;
import ed.inf.discovery.auxiliary.HopNode;
import ed.inf.discovery.auxiliary.function;
import ed.inf.grape.util.Compute;
import ed.inf.grape.util.IO;
import ed.inf.grape.util.KV;

/**
 * Data structure of partition, including a graph fragment and vertices with
 * crossing edges.
 * 
 * @author yecol
 *
 */

public class Partition extends Graph implements Serializable {

	private static final long serialVersionUID = -4757004627010733180L;

	private int partitionID;

	/** Node set in BF functions */

	private RoaringBitmap X;

	private RoaringBitmap XY;
	private RoaringBitmap XNotY;

	private int YCount = 0;
	private int notYCount = 0;

	private Set<Integer> freqEdgeLabels;

	private function iso_helper;

	/** pattern and its valid Xs */
	// private HashMap<Integer, RoaringBitmap> XYBitmapForPatterns;
	// private HashMap<Integer, RoaringBitmap> XNotYBitmapForPatterns;

	/** Statistics of current partition */

	static Logger log = LogManager.getLogger(Partition.class);

	public Partition(int partitionID) {
		super();
		this.partitionID = partitionID;
		this.X = new RoaringBitmap();
		this.XY = new RoaringBitmap();
		this.XNotY = new RoaringBitmap();

		this.freqEdgeLabels = new HashSet<Integer>();
		this.iso_helper = new function();
		// this.XYBitmapForPatterns = new HashMap<Integer, RoaringBitmap>();
		// this.XNotYBitmapForPatterns = new HashMap<Integer, RoaringBitmap>();
	}

	public int getPartitionID() {
		return partitionID;
	}

	/**
	 * First compute step, initial select and count.
	 * 
	 * @param xLabel
	 * @param yLabel
	 * @param xyEdgeType
	 */
	public void initWithPattern(Pattern pattern) {

		/** count X, Y, XY, notY */
		for (Node node : this.GetNodeSet().values()) {
			if (node.GetAttribute() == pattern.getX().attribute) {
				if (KV.ENABLE_FILTERX) {
					for (Node childNode : this.GetChildren(node)) {
						if (childNode.GetAttribute() == KV.QUERY_X_FILTER) {
							X.add(node.GetID());
							break;
						}
					}
				}

				else {
					X.add(node.GetID());
				}
			}
		}

		System.out.println("x1. node size = " + X.toArray().length);

		for (int nodeID : this.X) {

			/** count x with edge xy and xnoty but other */
			/** a x with edge xy count in xy but not xnoty */

			boolean hasXYEdgeType = false;
			boolean hasXY = false;

			for (Node childNode : this.GetChildren(this.FindNode(nodeID))) {
				if (Compute.getEdgeType(childNode.GetAttribute()) == Compute.getEdgeType(pattern
						.getY().attribute)) {
					hasXYEdgeType = true;
					if (childNode.GetAttribute() == pattern.getY().attribute) {
						XY.add(nodeID);
						hasXY = true;
						YCount++;
					} else {
						notYCount++;
					}
				}
			}

			if (hasXYEdgeType == true && hasXY == false) {
				XNotY.add(nodeID);
			}
		}

		pattern.setXCandidates(XY);
		pattern.setXnotYCandidates(XNotY);
		pattern.setSupportUB(XY.toArray().length);
		pattern.setYCount(YCount);
		pattern.setNotYCount(notYCount);
	}

	public int getYCount() {
		return YCount;
	}

	public int getNotYCount() {
		return notYCount;
	}

	public RoaringBitmap getX() {
		return X;
	}

	public RoaringBitmap getXNotY() {
		return XNotY;
	}

	public boolean isExtendibleAtR(int xID, int r) {

		Node center = this.FindNode(xID);
		PriorityQueue<HopNode> toVisit = new PriorityQueue<HopNode>();
		toVisit.add(new HopNode(center, 0));
		while (!toVisit.isEmpty()) {
			HopNode hn = toVisit.poll();
			if (hn.hop == r && hn.node.GetAttribute() == KV.PERSON_LABEL) {
				for (Node child : this.GetChildren(hn.node)) {
					if ((child.GetAttribute() != KV.PERSON_LABEL)
							&& this.freqEdgeLabels.contains(child.GetAttribute())) {
						return true;
					}
				}

			}

			else if (hn.hop < r) {
				for (Node n : this.GetChildren(hn.node)) {
					if (n.GetAttribute() == KV.PERSON_LABEL) {
						toVisit.add(new HopNode(n, hn.hop + 1));
					}
				}
			}
		}
		return false;
	}

	public int matchR(Pattern pattern) {

		// System.out.println(pattern);

		/** using x->y */

		// FIXME: not sure works correct.

		long start = System.currentTimeMillis();

		RoaringBitmap xset = new RoaringBitmap();

		/** Map storing edges to be mapping. HopFromX -> Edges */
		HashMap<Integer, HashSet<DefaultEdge>> oMappingEdges = new HashMap<Integer, HashSet<DefaultEdge>>();

		for (DefaultEdge e : pattern.getQ().edgeSet()) {
			int hop = pattern.getQ().getEdgeTarget(e).hop;
			if (!oMappingEdges.containsKey(hop)) {
				oMappingEdges.put(hop, new HashSet<DefaultEdge>());
			}
			oMappingEdges.get(hop).add(e);
		}
		// log.debug("match-debug" + oMappingEdges);

		for (int x : pattern.getXCandidates().toArray()) {

			// log.debug("match-debug" + "current x= " + x);
			// HashMap<Integer, HashSet<DefaultEdge>> mappingEdges =
			// SerializationUtils
			// .clone(oMappingEdges);

			boolean satisfy = true;

			/** Map storing edges to be mapping. PatternNodeID -> GraphNodeID */
			HashSet<Integer> lastMatches = new HashSet<Integer>();
			lastMatches.add(x);

			for (int i = 1; i <= KV.PARAMETER_B; i++) {

				// System.out.println("hop = " + i);
				if (!oMappingEdges.containsKey(i)) {
					// System.out.println("checked all");
					break;
				}

				HashSet<Integer> currentMatches = new HashSet<Integer>();
				for (DefaultEdge e : oMappingEdges.get(i)) {
					boolean edgeSatisfy = false;
					for (int lmatch : lastMatches) {

						if (this.FindNode(lmatch).GetAttribute() == pattern.getQ().getEdgeSource(e).attribute) {

							for (Node n : this.GetChildren(this.FindNode(lmatch))) {

								if (n.GetAttribute() == pattern.getQ().getEdgeTarget(e).attribute) {
									currentMatches.add(n.GetID());
									edgeSatisfy = true;
								}
							}
						}
					}
					if (edgeSatisfy == false) {
						satisfy = false;
					}
				}

				if (satisfy == false) {
					break;
				}

				lastMatches.clear();
				lastMatches.addAll(currentMatches);

			}

			if (satisfy == false) {
				continue;
			}

			else {
				xset.add(x);
			}

		}

		log.debug("pID=" + pattern.getPatternID() + " matchR using "
				+ (System.currentTimeMillis() - start) + "ms.");

		pattern.getXCandidates().and(xset);

		return xset.toArray().length;
	}

	public int matchQ(Pattern pattern) {

		long start = System.currentTimeMillis();

		RoaringBitmap xset = new RoaringBitmap();

		/** Map storing edges to be mapping. HopFromX -> Edges */
		HashMap<Integer, HashSet<DefaultEdge>> oMappingEdges = new HashMap<Integer, HashSet<DefaultEdge>>();

		for (DefaultEdge e : pattern.getQ().edgeSet()) {

			/**********************
			 * Different with MatchR Begin
			 ************************/
			if (pattern.getQ().getEdgeSource(e).nodeID == pattern.getX().nodeID
					&& pattern.getQ().getEdgeTarget(e).nodeID == pattern.getY().nodeID) {
				continue;
			}
			/**********************
			 * Different with MatchR End
			 **************************/

			else {

				int hop = pattern.getQ().getEdgeTarget(e).hop;
				if (!oMappingEdges.containsKey(hop)) {
					oMappingEdges.put(hop, new HashSet<DefaultEdge>());
				}
				oMappingEdges.get(hop).add(e);
			}
		}

		/**********************
		 * Different with MatchR Begin
		 ************************/
		if (oMappingEdges.size() == 0) {
			return pattern.getXNotYCandidates().toArray().length;
		}

		for (int x : pattern.getXNotYCandidates().toArray()) {
			/********************** Different with MatchR End **********************/

			boolean satisfy = true;

			/** Map storing edges to be mapping. PatternNodeID -> GraphNodeID */
			HashSet<Integer> lastMatches = new HashSet<Integer>();
			lastMatches.add(x);

			for (int i = 1; i <= KV.PARAMETER_B; i++) {

				// System.out.println("hop = " + i);
				if (!oMappingEdges.containsKey(i)) {
					// System.out.println("checked all");
					break;
				}

				HashSet<Integer> currentMatches = new HashSet<Integer>();
				for (DefaultEdge e : oMappingEdges.get(i)) {
					boolean edgeSatisfy = false;
					for (int lmatch : lastMatches) {

						if (this.FindNode(lmatch).GetAttribute() == pattern.getQ().getEdgeSource(e).attribute) {

							for (Node n : this.GetChildren(this.FindNode(lmatch))) {

								if (n.GetAttribute() == pattern.getQ().getEdgeTarget(e).attribute) {

									currentMatches.add(n.GetID());
									edgeSatisfy = true;
								}
							}
						}
					}
					if (edgeSatisfy == false) {
						satisfy = false;
					}
				}

				if (satisfy == false) {
					break;
				}

				lastMatches.clear();
				lastMatches.addAll(currentMatches);
			}

			if (satisfy == false) {
				continue;
			}

			else {
				xset.add(x);
			}

		}

		log.debug("pID=" + pattern.getPatternID() + " matchQ using "
				+ (System.currentTimeMillis() - start) + "ms.");

		/**********************
		 * Different with MatchR Begin
		 ************************/
		pattern.getXNotYCandidates().and(xset);
		/**********************
		 * Different with MatchR End
		 ************************/

		return xset.toArray().length;
	}

	public String getPartitionInfo() {
		return "pID = " + this.partitionID + " | vertices = " + this.GetNodeSize() + " | edges = "
				+ this.GetEdgeSize();
	}

	public String getCountInfo() {
		return "X.size = " + this.X.toArray().length + " | XY.size = " + this.XY.toArray().length
				+ " | XNotY.size = " + this.XNotY.toArray().length + " | YCount = " + this.YCount
				+ " | notYCount = " + this.notYCount;
	}

	public void setFreqEdgeLabels(Set<Integer> freqEdgeSet) {
		this.freqEdgeLabels = freqEdgeSet;
	}

	public Set<Integer> getFreqEdgeLabels() {
		return this.freqEdgeLabels;
	}

	// public int matchVF2R(Pattern p) {
	//
	// long start = System.currentTimeMillis();
	//
	// System.out.println("we begin match R");
	//
	// HashSet<Integer> validX = this.iso_helper.IsoCheck(p.toPGraph(), 0,
	// p.getXCandidates()
	// .toArray(), this);
	//
	// System.out.println("validx.size = " + validX.size());
	//
	// RoaringBitmap xset = new RoaringBitmap();
	// for (int x : validX) {
	// xset.add(x);
	// }
	//
	// p.getXCandidates().and(xset);
	// log.debug("pID=" + p.getPatternID() + " matchQ using "
	// + (System.currentTimeMillis() - start) + "ms.");
	//
	// return xset.toArray().length;
	// }
	//
	// public int matchVF2Q(Pattern p) {
	// long start = System.currentTimeMillis();
	//
	// HashSet<Integer> validX = this.iso_helper.IsoCheck(p.toQGraph(), 0,
	// p.getXCandidates()
	// .toArray(), this);
	//
	// RoaringBitmap xset = new RoaringBitmap();
	// for (int x : validX) {
	// xset.add(x);
	// }
	//
	// p.getXCandidates().and(xset);
	// log.debug("pID=" + p.getPatternID() + " matchQ using "
	// + (System.currentTimeMillis() - start) + "ms.");
	//
	// return xset.toArray().length;
	//
	// }

	public static void main(String[] args) {

		// Pattern [patternID=25, originID=0, partitionID=0, Q=([[NodeID:0, a=1,
		// h=0], [NodeID:1, a=2050041, h=1], [NodeID:2, a=2320003, h=1]],
		// [([NodeID:0, a=1, h=0],[NodeID:1, a=2050041, h=1]), ([NodeID:0, a=1,
		// h=0],[NodeID:2, a=2320003, h=1])]), x=[NodeID:0, a=1, h=0],
		// y=[NodeID:1, a=2050041, h=1], diameter=2]

		// For MatchR and MatchQ Test.

		Pattern p = new Pattern(0);
		p.initialXYEdge(1, 2050041);

		System.out.println(p.toString());
		// // p.expend1Node1EdgeAsChildFromFixedNode(0, 2430010);
		// // p.expend1Node1Edge(1, 201);
		// // p.expend1Node1Edge(1, 1);
		// // p.expend1Node1Edge(1, 1);
		//
		// System.out.println(p.toString());
		//
		// KV.PARAMETER_B = 4;
		//
		Partition partition = IO.loadPartitionFromVEFile(0, "dataset/graph-0");
		// // Partition partition = IO.loadPartitionFromVEFile(0,
		// "dataset/test");
		partition.initWithPattern(p);
		System.out.println(partition.getCountInfo());
		// System.out.println("final ret = " + partition.matchR(p));
		// System.out.println("final ret = " + partition.matchQ(p));

		// executor exe = new executor();
		// Graph p = exe.patternGen();
		// Graph g = exe.graphGen();

		function f = new function();
		// int[] v_index_set = { 1, 2, 10, 15, 17 };
		long start = System.currentTimeMillis();

		p.toPGraph().Display();
		HashSet<Integer> set = f.IsoCheck(p.toPGraph(), 0, partition.X.toArray(), partition);

		System.out.println(set.size());
		System.out.println("using time  = " + (System.currentTimeMillis() - start) + "ms");

		// Vector<Integer> result = f.IsoCheck(p, 0,
		// partition.X.toArray(), (Graph) partition);
		//

		// for (int i : set) {
		// System.out.println(i);
		// }

		// long start = System.currentTimeMillis();
		// int count1 = 0;
		// int i = 0;
		// for (int index : partition.X) {
		// i++;
		// // if (i < 10) {
		// if (partition.isExtendibleAtR(index, 1)) {
		// count1++;
		// }
		// }
		// System.out.println("count1 = " + count1);
		// System.out.println("using time  = "
		// + (System.currentTimeMillis() - start) + "ms");
		//
		// Node n = partition.FindNode(0);
		// PriorityQueue<HopNode> q = new PriorityQueue<HopNode>();
		// q.add(new HopNode(n, 4));
		// q.add(new HopNode(n, 2));
		// q.add(new HopNode(n, 3));
		// q.add(new HopNode(n, 6));
		//
		// while (!q.isEmpty()) {
		// System.out.println(q.poll().hop);
		// }

	}
}
