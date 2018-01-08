package io.lacuna.bifurcan;

import java.util.Iterator;
import java.util.function.BinaryOperator;

import static io.lacuna.bifurcan.Graphs.MERGE_LAST_WRITE_WINS;

/**
 * @author ztellman
 */
public interface IGraph<V, E> extends ILinearizable<IGraph<V, E>>, IForkable<IGraph<V, E>> {

  interface IEdge<V, E> {
    V from();
    V to();
    E value();
  }

  /**
   * @return the set of all vertices in the graph
   */
  ISet<V> vertices();

  /**
   * @return the value of the edge between {@code from} and {@code to}
   * @throws IllegalArgumentException if no such edge exists
   */
  E edge(V from, V to);

  /**
   * @return the set of all incoming edges to {@code vertex}
   */
  ISet<V> in(V vertex);

  /**
   * @return the set of all outgoing edges from {@code vertex}
   */
  ISet<V> out(V vertex);

  /**
   * @param from the source of the edge
   * @param to the destination of the edge
   * @param edge the value of the edge
   * @param merge the merge function for the edge values, if an edge already exists
   * @return a graph containing the new edge
   */
  IGraph<V, E> link(V from, V to, E edge, BinaryOperator<E> merge);

  /**
   * @return a graph without any edge between {@code from} and {@code to}
   */
  IGraph<V, E> unlink(V from, V to);

  /**
   * @return a graph with {@code vertex} added
   */
  IGraph<V, E> add(V vertex);

  /**
   * @return a graph with {@code vertex} removed, as well as all incoming and outgoing edges
   */
  IGraph<V, E> remove(V vertex);

  default IGraph<V, E> select(ISet<V> vertices) {
    IGraph<V, E> g = this.linear();
    for (V v : vertices().difference(vertices)) {
      g = g.remove(v);
    }
    return g.forked();
  }

  boolean isLinear();

  // polymorphic utility methods

  default IGraph<V, E> add(IEdge<V, E> edge) {
    return link(edge.from(), edge.to(), edge.value());
  }

  default IGraph<V, E> remove(IEdge<V, E> edge) {
    return unlink(edge.from(), edge.to());
  }

  default IGraph<V, E> link(V from, V to, E edge) {
    return link(from, to, edge, (BinaryOperator<E>) MERGE_LAST_WRITE_WINS);
  }

  default IGraph<V, E> link(V from, V to) {
    return link(from, to, null, (BinaryOperator<E>) MERGE_LAST_WRITE_WINS);
  }

  default IGraph<V, E> merge(IGraph<V, E> graph, BinaryOperator<E> merge) {
    return Graphs.merge(this, graph, merge);
  }

  default IGraph<V, E> merge(IGraph<V, E> graph) {
    return merge(graph, (BinaryOperator<E>) MERGE_LAST_WRITE_WINS);
  }

}
