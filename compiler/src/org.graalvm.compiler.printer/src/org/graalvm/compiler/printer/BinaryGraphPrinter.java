/*
 * Copyright (c) 2011, 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.printer;

import static org.graalvm.compiler.graph.Edges.Type.Inputs;
import static org.graalvm.compiler.graph.Edges.Type.Successors;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import jdk.vm.ci.meta.ResolvedJavaField;

import org.graalvm.compiler.api.replacements.SnippetReflectionProvider;
import org.graalvm.compiler.bytecode.Bytecode;
import org.graalvm.compiler.core.common.cfg.BlockMap;
import org.graalvm.compiler.debug.DebugOptions;
import org.graalvm.compiler.graph.CachedGraph;
import org.graalvm.compiler.graph.Edges;
import org.graalvm.compiler.graph.Graph;
import org.graalvm.compiler.graph.InputEdges;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.NodeClass;
import org.graalvm.compiler.graph.NodeMap;
import org.graalvm.compiler.nodeinfo.InputType;
import org.graalvm.compiler.nodes.AbstractBeginNode;
import org.graalvm.compiler.nodes.AbstractEndNode;
import org.graalvm.compiler.nodes.AbstractMergeNode;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.ControlSinkNode;
import org.graalvm.compiler.nodes.ControlSplitNode;
import org.graalvm.compiler.nodes.FixedNode;
import org.graalvm.compiler.nodes.PhiNode;
import org.graalvm.compiler.nodes.ProxyNode;
import org.graalvm.compiler.nodes.VirtualState;
import org.graalvm.compiler.nodes.cfg.Block;
import org.graalvm.compiler.nodes.cfg.ControlFlowGraph;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Signature;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.graph.NodeSourcePosition;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.phases.schedule.SchedulePhase;

public class BinaryGraphPrinter extends AbstractGraphPrinter<BinaryGraphPrinter.GraphInfo, Node, NodeClass<?>, Edges, Block, ResolvedJavaMethod, ResolvedJavaField, Signature, NodeSourcePosition, InputType>
                implements GraphPrinter {
    private SnippetReflectionProvider snippetReflection;

    public BinaryGraphPrinter(WritableByteChannel channel, SnippetReflectionProvider snippetReflection) throws IOException {
        super(channel);
        this.snippetReflection = snippetReflection;
    }

    @Override
    public SnippetReflectionProvider getSnippetReflectionProvider() {
        return snippetReflection;
    }

    @Override
    public String formatTitle(GraphInfo graph, int id, String format, Object... args) {
        Object[] newArgs = GraphPrinter.super.simplifyClassArgs(args);
        return id + ": " + String.format(format, newArgs);
    }

    @Override
    public void beginGroup(DebugContext debug, String name, String shortName, ResolvedJavaMethod method, int bci, Map<Object, Object> properties) throws IOException {
        beginGroup(new GraphInfo(debug, null), name, shortName, method, 0, properties);
    }

    @Override
    protected ResolvedJavaMethod findMethod(Object object) {
        if (object instanceof Bytecode) {
            return ((Bytecode) object).getMethod();
        } else if (object instanceof ResolvedJavaMethod) {
            return ((ResolvedJavaMethod) object);
        } else {
            return null;
        }
    }

    @Override
    protected NodeClass<?> findNodeClass(Object obj) {
        if (obj instanceof NodeClass<?>) {
            return (NodeClass<?>) obj;
        }
        if (obj instanceof Node) {
            return ((Node) obj).getNodeClass();
        }
        return null;
    }

    @Override
    protected Class<?> findJavaClass(NodeClass<?> node) {
        return node.getJavaClass();
    }

    @Override
    protected String findNameTemplate(NodeClass<?> nodeClass) {
        return nodeClass.getNameTemplate();
    }

    @Override
    protected final GraphInfo findGraph(GraphInfo currrent, Object obj) {
        if (obj instanceof Graph) {
            return new GraphInfo(currrent.debug, (Graph) obj);
        } else if (obj instanceof CachedGraph) {
            return new GraphInfo(currrent.debug, ((CachedGraph<?>) obj).getReadonlyCopy());
        } else {
            return null;
        }
    }

    @Override
    protected int findNodeId(Node n) {
        return getNodeId(n);
    }

    @Override
    protected final Edges findEdges(Node node, boolean dumpInputs) {
        NodeClass<?> nodeClass = node.getNodeClass();
        return findClassEdges(nodeClass, dumpInputs);
    }

    @Override
    protected final Edges findClassEdges(NodeClass<?> nodeClass, boolean dumpInputs) {
        return nodeClass.getEdges(dumpInputs ? Inputs : Successors);
    }

    @SuppressWarnings("deprecation")
    private static int getNodeId(Node node) {
        return node == null ? -1 : node.getId();
    }

    @Override
    protected List<Node> findBlockNodes(GraphInfo info, Block block) {
        return info.blockToNodes.get(block);
    }

    @Override
    protected int findBlockId(Block sux) {
        return sux.getId();
    }

    @Override
    protected List<Block> findBlockSuccessors(Block block) {
        return Arrays.asList(block.getSuccessors());
    }

    @Override
    protected Iterable<Node> findNodes(GraphInfo info) {
        return info.graph.getNodes();
    }

    @Override
    protected int findNodesCount(GraphInfo info) {
        return info.graph.getNodeCount();
    }

    @Override
    protected void findNodeProperties(Node node, Map<Object, Object> props, GraphInfo info) {
        node.getDebugProperties(props);
        Graph graph = info.graph;
        ControlFlowGraph cfg = info.cfg;
        NodeMap<Block> nodeToBlocks = info.nodeToBlocks;
        if (cfg != null && DebugOptions.PrintGraphProbabilities.getValue(graph.getOptions()) && node instanceof FixedNode) {
            try {
                props.put("probability", cfg.blockFor(node).probability());
            } catch (Throwable t) {
                props.put("probability", 0.0);
                props.put("probability-exception", t);
            }
        }

        try {
            props.put("NodeCost-Size", node.estimatedNodeSize());
            props.put("NodeCost-Cycles", node.estimatedNodeCycles());
        } catch (Throwable t) {
            props.put("node-cost-exception", t.getMessage());
        }

        if (nodeToBlocks != null) {
            Object block = getBlockForNode(node, nodeToBlocks);
            if (block != null) {
                props.put("node-to-block", block);
            }
        }

        if (node instanceof ControlSinkNode) {
            props.put("category", "controlSink");
        } else if (node instanceof ControlSplitNode) {
            props.put("category", "controlSplit");
        } else if (node instanceof AbstractMergeNode) {
            props.put("category", "merge");
        } else if (node instanceof AbstractBeginNode) {
            props.put("category", "begin");
        } else if (node instanceof AbstractEndNode) {
            props.put("category", "end");
        } else if (node instanceof FixedNode) {
            props.put("category", "fixed");
        } else if (node instanceof VirtualState) {
            props.put("category", "state");
        } else if (node instanceof PhiNode) {
            props.put("category", "phi");
        } else if (node instanceof ProxyNode) {
            props.put("category", "proxy");
        } else {
            if (node instanceof ConstantNode) {
                ConstantNode cn = (ConstantNode) node;
                updateStringPropertiesForConstant(props, cn);
            }
            props.put("category", "floating");
        }
    }

    private Object getBlockForNode(Node node, NodeMap<Block> nodeToBlocks) {
        if (nodeToBlocks.isNew(node)) {
            return "NEW (not in schedule)";
        } else {
            Block block = nodeToBlocks.get(node);
            if (block != null) {
                return block.getId();
            } else if (node instanceof PhiNode) {
                return getBlockForNode(((PhiNode) node).merge(), nodeToBlocks);
            }
        }
        return null;
    }

    @Override
    protected void findExtraNodes(Node node, Collection<? super Node> extraNodes) {
        if (node instanceof AbstractMergeNode) {
            AbstractMergeNode merge = (AbstractMergeNode) node;
            for (PhiNode phi : merge.phis()) {
                extraNodes.add(phi);
            }
        }
    }

    @Override
    protected boolean hasPredecessor(Node node) {
        return node.predecessor() != null;
    }

    @Override
    protected List<Block> findBlocks(GraphInfo graph) {
        return graph.blocks;
    }

    @Override
    public void print(DebugContext debug, Graph graph, Map<Object, Object> properties, int id, String format, Object... args) throws IOException {
        print(new GraphInfo(debug, graph), properties, 0, format, args);
    }

    @Override
    protected int findSize(Edges edges) {
        return edges.getCount();
    }

    @Override
    protected boolean isDirect(Edges edges, int i) {
        return i < edges.getDirectCount();
    }

    @Override
    protected String findName(Edges edges, int i) {
        return edges.getName(i);
    }

    @Override
    protected InputType findType(Edges edges, int i) {
        return ((InputEdges) edges).getInputType(i);
    }

    @Override
    protected Node findNode(GraphInfo graph, Node node, Edges edges, int i) {
        return Edges.getNode(node, edges.getOffsets(), i);
    }


    @Override
    protected List<Node> findNodes(GraphInfo graph, Node node, Edges edges, int i) {
        return Edges.getNodeList(node, edges.getOffsets(), i);
    }

    @Override
    protected Object findEnumClass(Object enumValue) {
        if (enumValue instanceof Enum) {
            return enumValue.getClass();
        }
        return null;
    }

    @Override
    protected int findEnumOrdinal(Object obj) {
        if (obj instanceof Enum<?>) {
            return ((Enum<?>) obj).ordinal();
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    @Override
    protected String[] findEnumTypeValues(Object clazz) {
        if (clazz instanceof Class<?>) {
            Class<? extends Enum<?>> enumClass = (Class<? extends Enum<?>>) clazz;
            Enum<?>[] constants = enumClass.getEnumConstants();
            if (constants != null) {
                String[] names = new String[constants.length];
                for (int i = 0; i < constants.length; i++) {
                    names[i] = constants[i].name();
                }
                return names;
            }
        }
        return null;
    }

    @Override
    protected String findJavaTypeName(Object obj) {
        if (obj instanceof Class<?>) {
            return ((Class<?>)obj).getName();
        }
        if (obj instanceof ResolvedJavaType) {
            return ((ResolvedJavaType) obj).getName();
        }
        return null;
    }

    @Override
    protected byte[] findMethodCode(ResolvedJavaMethod method) {
        return method.getCode();
    }

    @Override
    protected int findMethodModifiers(ResolvedJavaMethod method) {
        return method.getModifiers();
    }

    @Override
    protected Signature findMethodSignature(ResolvedJavaMethod method) {
        return method.getSignature();
    }

    @Override
    protected String findMethodName(ResolvedJavaMethod method) {
        return method.getName();
    }

    @Override
    protected Object findMethodDeclaringClass(ResolvedJavaMethod method) {
        return method.getDeclaringClass();
    }

    @Override
    protected int findFieldModifiers(ResolvedJavaField field) {
        return field.getModifiers();
    }

    @Override
    protected String findFieldTypeName(ResolvedJavaField field) {
        return field.getType().getName();
    }

    @Override
    protected String findFieldName(ResolvedJavaField field) {
        return field.getName();
    }

    @Override
    protected Object findFieldDeclaringClass(ResolvedJavaField field) {
        return field.getDeclaringClass();
    }

    @Override
    protected ResolvedJavaField findJavaField(Object object) {
        if (object instanceof ResolvedJavaField) {
            return (ResolvedJavaField) object;
        }
        return null;
    }

    @Override
    protected Signature findSignature(Object object) {
        if (object instanceof Signature) {
            return (Signature) object;
        }
        return null;
    }

    @Override
    protected int findSignatureParameterCount(Signature signature) {
        return signature.getParameterCount(false);
    }

    @Override
    protected String findSignatureParameterTypeName(Signature signature, int index) {
        return signature.getParameterType(index, null).getName();
    }

    @Override
    protected String findSignatureReturnTypeName(Signature signature) {
        return signature.getReturnType(null).getName();
    }

    @Override
    protected NodeSourcePosition findNodeSourcePosition(Object object) {
        if (object instanceof NodeSourcePosition) {
            return (NodeSourcePosition) object;
        }
        return null;
    }

    @Override
    protected ResolvedJavaMethod findNodeSourcePositionMethod(NodeSourcePosition pos) {
        return pos.getMethod();
    }

    @Override
    protected NodeSourcePosition findNodeSourcePositionCaller(NodeSourcePosition pos) {
        return pos.getCaller();
    }

    @Override
    protected int findNodeSourcePositionBCI(NodeSourcePosition pos) {
        return pos.getBCI();
    }

    @Override
    protected StackTraceElement findMethodStackTraceElement(ResolvedJavaMethod method, int bci, NodeSourcePosition pos) {
        return method.asStackTraceElement(bci);
    }

    static final class GraphInfo {
        final DebugContext debug;
        final Graph graph;
        final ControlFlowGraph cfg;
        final BlockMap<List<Node>> blockToNodes;
        final NodeMap<Block> nodeToBlocks;
        final List<Block> blocks;

        private GraphInfo(DebugContext debug, Graph graph) {
            this.debug = debug;
            this.graph = graph;
            StructuredGraph.ScheduleResult scheduleResult = null;
            if (graph instanceof StructuredGraph) {

                StructuredGraph structuredGraph = (StructuredGraph) graph;
                scheduleResult = structuredGraph.getLastSchedule();
                if (scheduleResult == null) {

                    // Also provide a schedule when an error occurs
                    if (DebugOptions.PrintGraphWithSchedule.getValue(graph.getOptions()) || debug.contextLookup(Throwable.class) != null) {
                        try {
                            SchedulePhase schedule = new SchedulePhase(graph.getOptions());
                            schedule.apply(structuredGraph);
                            scheduleResult = structuredGraph.getLastSchedule();
                        } catch (Throwable t) {
                        }
                    }

                }
            }
            cfg = scheduleResult == null ? debug.contextLookup(ControlFlowGraph.class) : scheduleResult.getCFG();
            blockToNodes = scheduleResult == null ? null : scheduleResult.getBlockToNodesMap();
            nodeToBlocks = scheduleResult == null ? null : scheduleResult.getNodeToBlockMap();
            blocks = cfg == null ? null : Arrays.asList(cfg.getBlocks());
        }
    }

}
