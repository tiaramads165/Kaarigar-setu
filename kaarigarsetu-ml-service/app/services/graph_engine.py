from __future__ import annotations

import networkx as nx


class GraphEngine:
    def scores(self, nodes: list[str], edges: list[object]) -> dict[str, float]:
        graph = nx.DiGraph()
        graph.add_nodes_from(nodes)
        for edge in edges:  # parallel edges between the same pair add up instead of overwriting each other
            if graph.has_edge(edge.source, edge.target):
                graph[edge.source][edge.target]["weight"] += edge.weight
            else:
                graph.add_edge(edge.source, edge.target, weight=edge.weight)
        if not graph.nodes:
            return {}
        return {node: round(score, 6) for node, score in nx.pagerank(graph, weight="weight").items()}

    @staticmethod
    def scaled(scores: dict[str, float]) -> dict[str, float]:
        """0-100 where the most trusted node is 100 (PageRank scores sum to 1 and are not human friendly)."""
        top = max(scores.values(), default=0.0)
        return {node: round(100 * value / top, 2) if top else 0.0 for node, value in scores.items()}
