package io.lacuna.bifurcan;

import io.lacuna.bifurcan.diffs.DiffMap;
import io.lacuna.bifurcan.durable.codecs.HashMap;
import io.lacuna.bifurcan.utils.Iterators;

import java.nio.file.Path;
import java.util.*;
import java.util.function.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * @author ztellman
 */
@SuppressWarnings("unchecked")
public interface IMap<K, V> extends
    ICollection<IMap<K, V>, IEntry<K, V>>,
    Function<K, V> {

  abstract class Mixin<K, V> implements IMap<K, V> {
    protected int hash = -1;

    @Override
    public int hashCode() {
      if (hash == -1) {
        hash = (int) Maps.hash(this);
      }
      return hash;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof IMap) {
        return Maps.equals(this, (IMap<K, V>) obj);
      } else {
        return false;
      }
    }

    @Override
    public String toString() {
      return Maps.toString(this);
    }

    @Override
    public IMap<K, V> clone() {
      return this;
    }
  }

  interface Durable<K, V> extends IMap<K, V>, IDurableCollection {
    IDurableEncoding.Map encoding();
  }

  /**
   * @return the hash function used by the map
   */
  ToLongFunction<K> keyHash();

  /**
   * @return the key equality semantics used by the map
   */
  BiPredicate<K, K> keyEquality();

  /**
   * @return the value under {@code key}, or {@code defaultValue} if there is no such key
   */
  default V get(K key, V defaultValue) {
    OptionalLong idx = indexOf(key);
    return idx.isPresent()
        ? nth(idx.getAsLong()).value()
        : defaultValue;
  }

  /**
   * @return an {@link Optional} containing the value under {@code key}, or nothing if the value is {@code null} or
   * is not contained within the map.
   */
  default Optional<V> get(K key) {
    return Optional.ofNullable(get(key, null));
  }

  /**
   * @return the value under {@code key}, or one generated by {@code f} if there is no such key
   */
  default V getOrCreate(K key, Supplier<V> f) {
    V val = get(key, null);
    if (val == null) {
      val = f.get();
      put(key, val);
    }
    return val;
  }

  /**
   * @return true if {@code key} is in the map, false otherwise
   */
  default boolean contains(K key) {
    return indexOf(key).isPresent();
  }

  /**
   * @return a list containing all the entries within the map
   */
  default IList<IEntry<K, V>> entries() {
    return Lists.from(size(), this::nth, this::iterator);
  }

  /**
   * @return the index of {@code key} within the collection, if it's present
   */
  OptionalLong indexOf(K key);

  /**
   * @return a set representing all keys in the map
   */
  default ISet<K> keys() {
    return Sets.from(Lists.lazyMap(entries(), IEntry::key), this::indexOf);
  }

  /**
   * @return a list representing all values in the map
   */
  default IList<V> values() {
    return Lists.lazyMap(entries(), IEntry::value);
  }

  /**
   * @param f   a function which transforms the values
   * @param <U> the new type of the values
   * @return a transformed map which shares the same equality semantics
   */
  default <U> IMap<K, U> mapValues(BiFunction<K, V, U> f) {
    Map<K, U> m = new Map<K, U>(keyHash(), keyEquality()).linear();
    this.forEach(e -> m.put(e.key(), f.apply(e.key(), e.value())));
    return isLinear() ? m : m.forked();
  }

  /**
   * @return true if this map contains all elements in {@code set}
   */
  default boolean containsAll(ISet<K> set) {
    return set.elements().stream().allMatch(this::contains);
  }

  /**
   * @return true if this map contains all keys in {@code map}
   */
  default boolean containsAll(IMap<K, ?> map) {
    return containsAll(map.keys());
  }

  /**
   * @return true if this map contains any element in {@code set}
   */
  default boolean containsAny(ISet<K> set) {
    return set.elements().stream().anyMatch(this::contains);
  }

  /**
   * @return true if this map contains any element in {@code map}
   */
  default boolean containsAny(IMap<K, ?> map) {
    return containsAny(map.keys());
  }

  /**
   * @return true, if the collection is linear
   */
  default boolean isLinear() {
    return false;
  }

  /**
   * @return the collection, represented as a normal Java map, which will throw {@link UnsupportedOperationException}
   * on writes
   */
  default java.util.Map<K, V> toMap() {
    return Maps.toMap(this);
  }

  /**
   * @return an iterator over all entries in the map
   */
  default Iterator<IEntry<K, V>> iterator(long startIndex) {
    return Iterators.range(startIndex, size(), this::nth);
  }

  /**
   * @return an iterator over all entries, sorted by their hash
   */
  default Iterator<IEntry.WithHash<K, V>> hashSortedEntries() {
    // TODO: figure out how to make this place nicely with the TempStream lifecycle
    return HashMap.sortIndexedEntries(this, keyHash());
  }

  /**
   * @return a {@link java.util.stream.Stream}, representing the entries in the map
   */
  default Stream<IEntry<K, V>> stream() {
    return StreamSupport.stream(spliterator(), false);
  }

  @Override
  default Spliterator<IEntry<K, V>> spliterator() {
    return Spliterators.spliterator(iterator(), size(), Spliterator.DISTINCT);
  }

  /**
   * @param b       another map
   * @param mergeFn a function which, in the case of key collisions, takes two values and returns the merged result
   * @return a new map representing the merger of the two maps
   */
  default IMap<K, V> merge(IMap<K, V> b, BinaryOperator<V> mergeFn) {
    return Maps.merge(this, b, mergeFn);
  }

  /**
   * @return a new map representing the current map, less the keys in {@code keys}
   */
  default IMap<K, V> difference(ISet<K> keys) {
    return Maps.difference(this, keys);
  }

  /**
   * @return a new map representing the current map, but only with the keys in {@code keys}
   */
  default IMap<K, V> intersection(ISet<K> keys) {
    IMap<K, V> result = Maps.intersection(new Map<K, V>(keyHash(), keyEquality()).linear(), this, keys);
    return isLinear() ? result : result.forked();
  }

  /**
   * @return a combined map, with the values from {@code m} shadowing those in this amp
   */
  default IMap<K, V> union(IMap<K, V> m) {
    return merge(m, Maps.MERGE_LAST_WRITE_WINS);
  }

  /**
   * @return a new map representing the current map, less the keys in {@code m}
   */
  default IMap<K, V> difference(IMap<K, ?> m) {
    return difference(m.keys());
  }

  /**
   * @return a new map representing the current map, but only with the keys in {@code m}
   */
  default IMap<K, V> intersection(IMap<K, ?> m) {
    return intersection(m.keys());
  }

  /**
   * @param merge a function which will be invoked if there is a pre-existing value under {@code key}, with the current
   *              value as the first argument and new value as the second, to determine the combined result
   * @return an updated map with {@code value} under {@code key}
   */
  default IMap<K, V> put(K key, V value, BinaryOperator<V> merge) {
    return new DiffMap<>(this).put(key, value, merge);
  }

  /**
   * @param update a function which takes the existing value, or {@code null} if none exists, and returns an updated
   *               value.
   * @return an updated map with {@code update(value)} under {@code key}.
   */
  default IMap<K, V> update(K key, UnaryOperator<V> update) {
    return this.put(key, update.apply(this.get(key, null)));
  }

  /**
   * @return an updated map with {@code value} stored under {@code key}
   */
  default IMap<K, V> put(K key, V value) {
    return put(key, value, Maps.MERGE_LAST_WRITE_WINS);
  }

  /**
   * @return an updated map that does not contain {@code key}
   */
  default IMap<K, V> remove(K key) {
    return new DiffMap<>(this).remove(key);
  }

  @Override
  default IMap<K, V> forked() {
    return this;
  }

  @Override
  default IMap<K, V> linear() {
    return new DiffMap<>(this).linear();
  }

  @Override
  default IList<? extends IMap<K, V>> split(int parts) {
    return keys()
        .split(parts)
        .stream()
        .map(ks -> ks.zip(this))
        .collect(Lists.collector());
  }

  /**
   * @param m      another map
   * @param equals a predicate which checks value equalities
   * @return true, if the maps are equivalent
   */
  default boolean equals(IMap<K, V> m, BiPredicate<V, V> equals) {
    return Maps.equals(this, m, equals);
  }

  /**
   * @return the corresponding value
   * @throws IllegalArgumentException if no such key is inside the map
   */
  @Override
  default V apply(K k) {
    V defaultVal = (V) new Object();
    V val = get(k, defaultVal);
    if (val == defaultVal) {
      throw new IllegalArgumentException("key not found");
    }
    return val;
  }

  @Override
  default IMap.Durable<K, V> save(IDurableEncoding encoding, Path directory) {
    return (IMap.Durable<K, V>) ICollection.super.save(encoding, directory);
  }
}
