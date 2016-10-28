package io.lacuna.bifurcan;

import io.lacuna.bifurcan.Maps.Entry;
import io.lacuna.bifurcan.utils.Bits;

import java.util.Objects;
import java.util.Optional;
import java.util.function.*;

import static io.lacuna.bifurcan.utils.Bits.log2Ceil;

/**
 * A hash-map implementation which uses Robin Hood hashing for placement, and allows for customized hashing and equality
 * semantics.  Performance is moderately faster than {@code java.util.HashMap}, and much better in the worst case of
 * poor hash distribution.
 * <p>
 * Unlike {@code HashMap.entrySet()}, the {@code entries()} method is O(1), returning an IList that proxies through to the
 * underlying {@code entries}, which are in a densely packed array.  Partitioning this list is the most efficient way to
 * process the collection in parallel.
 * <p>
 * However, {@code LinearMap} also exposes O(N) {@code partition()} and {@code merge()} methods, which despite their
 * asymptotic complexity can be quite fast in practice.  The appropriate way to partition this collection will depend
 * on the use case.
 *
 * @author ztellman
 */
@SuppressWarnings("unchecked")
public class LinearMap<K, V> implements IMap<K, V>, IPartitionable<LinearMap<K, V>> {

  public static final int MAX_CAPACITY = (1 << 30) - 1;
  private static final float LOAD_FACTOR = 0.95f;

  private static final int NONE = 0;
  private static final int FALLBACK = 1;

  private final ToIntFunction<K> hashFn;
  private final BiPredicate<K, K> equalsFn;
  private final int indexMask;

  private final long[] table;

  private final IEntry<K, V>[] entries;
  private int size;

  public LinearMap() {
    this(8);
  }

  public LinearMap(int initialCapacity) {
    this(initialCapacity, Objects::hashCode, Objects::equals);
  }

  /**
   * Creates a map from an existing {@code java.util.Map}, using the default Java hashing and equality mechanisms.
   *
   * @param m an existing {@code java.util.Map}
   */
  public LinearMap(java.util.Map<K, V> m) {
    this(m.size());
    m.entrySet().forEach(e -> put(e.getKey(), e.getValue()));
  }

  public LinearMap(IMap<K, V> m) {
    this((int) m.size());
    m.entries().stream().forEach(e -> put(e.key(), e.value()));
  }

  public LinearMap(int initialCapacity, ToIntFunction<K> hashFn, BiPredicate<K, K> equalsFn) {

    if (initialCapacity > MAX_CAPACITY) {
      throw new IllegalArgumentException("initialCapacity cannot be larger than " + MAX_CAPACITY);
    }

    initialCapacity = Math.max(4, initialCapacity);
    int tableLength = (int) (1L << log2Ceil((long) Math.ceil(initialCapacity / LOAD_FACTOR)));

    this.indexMask = tableLength - 1;
    this.hashFn = hashFn;
    this.equalsFn = equalsFn;

    this.entries = new IEntry[initialCapacity];
    this.table = new long[tableLength];
    this.size = 0;
  }

  private LinearMap<K, V> resize(int capacity) {
    if (capacity > MAX_CAPACITY) {
      throw new IllegalStateException("the map cannot be larger than " + MAX_CAPACITY);
    }

    LinearMap<K, V> m = new LinearMap<>(capacity, hashFn, equalsFn);
    System.arraycopy(entries, 0, m.entries, 0, size);
    m.size = size;

    for (int idx = 0; idx < table.length; idx++) {
      long row = table[idx];
      if (Row.populated(row)) {
        m.constructPut(Row.hash(row), Row.entryIndex(row));
      }
    }

    return m;
  }

  private int indexFor(int hash, K key) {
    for (int idx = indexFor(hash), dist = 0; ; idx = nextIndex(idx), dist++) {
      long row = table[idx];
      int currHash = Row.hash(row);
      if (currHash == hash && !Row.tombstone(row) && equalsFn.test(key, entries[Row.entryIndex(row)].key())) {
        return idx;
      } else if (currHash == NONE || dist > probeDistance(currHash, idx)) {
        return -1;
      }
    }
  }

  private void constructPut(int hash, int entryIndex) {
    for (int idx = indexFor(hash), dist = 0; ; idx = nextIndex(idx), dist++) {
      long row = table[idx];
      int currHash = Row.hash(row);
      if (currHash == NONE) {
        table[idx] = Row.construct(hash, entryIndex);
        break;
      } else if (dist > probeDistance(currHash, idx)) {
        int currEntryIndex = Row.entryIndex(row);
        table[idx] = Row.construct(hash, entryIndex);

        dist = probeDistance(currHash, idx);
        entryIndex = currEntryIndex;
        hash = currHash;
      }
    }
  }

  // factored out for better inlining
  private boolean putCheckEquality(int idx, IEntry<K, V> entry) {
    long row = table[idx];
    int currIndex = Row.entryIndex(row);
    IEntry<K, V> currEntry = entries[currIndex];
    if (equalsFn.test(entry.key(), currEntry.key())) {
      entries[currIndex] = entry;
      table[idx] = Row.removeTombstone(row);
      return true;
    } else {
      return false;
    }
  }

  private void put(int hash, IEntry<K, V> entry) {
    for (int idx = indexFor(hash), dist = 0; ; idx = nextIndex(idx), dist++) {
      long row = table[idx];
      int currHash = Row.hash(row);
      boolean isNone = currHash == NONE;
      boolean currTombstone = Row.tombstone(row);

      if (currHash == hash && !currTombstone && putCheckEquality(idx, entry)) {
        break;
      } else if (isNone || dist > probeDistance(currHash, idx)) {
        // if it's empty, or it's a tombstone and there's no possible exact match further down, use it
        if (isNone || currTombstone) {
          entries[size] = entry;
          table[idx] = Row.construct(hash, size);
          size++;
          break;
        }

        // we deserve this location more, so swap them out
        int currEntryIndex = Row.entryIndex(row);
        IEntry<K, V> currEntry = entries[currEntryIndex];
        entries[currEntryIndex] = entry;
        table[idx] = Row.construct(hash, currEntryIndex);

        dist = probeDistance(currHash, idx);
        entry = currEntry;
        hash = currHash;
      }
    }
  }

  @Override
  public IMap<K, V> put(K key, V value) {
    if (size == entries.length) {
      return resize(entries.length << 1).put(key, value);
    } else {
      put(keyHash(key), new Entry<>(key, value));
      return this;
    }
  }

  @Override
  public IMap<K, V> remove(K key) {
    int idx = indexFor(keyHash(key), key);
    if (idx >= 0) {
      long row = table[idx];
      size--;
      int entryIndex = Row.entryIndex(row);
      if (entryIndex != size) {
        IEntry<K, V> lastEntry = entries[size];
        K lastKey = lastEntry.key();
        int lastIdx = indexFor(keyHash(lastKey), lastKey);
        table[lastIdx] = Row.construct(Row.hash(table[lastIdx]), entryIndex);
        entries[entryIndex] = lastEntry;
      }
      table[idx] = Row.addTombstone(row);
      entries[size] = null;
    }

    return this;
  }

  private IEntry<K, V> entryFor(K key){
    int hash = keyHash(key);

    for (int idx = indexFor(hash), dist = 0; ; idx = nextIndex(idx), dist++) {
      long row = table[idx];
      int currHash = Row.hash(row);
      if (currHash == hash && !Row.tombstone(row)) {
        IEntry<K, V> entry = entries[Row.entryIndex(row)];
        if (equalsFn.test(entry.key(), key)) {
          return entry;
        }
      } else if (currHash == NONE || dist > probeDistance(currHash, idx)) {
        return null;
      }
    }
  }

  @Override
  public boolean contains(K key) {
    return entryFor(key) != null;
  }

  @Override
  public Optional<V> get(K key) {
    IEntry<K, V> entry = entryFor(key);
    return entry != null ? Optional.ofNullable(entry.value()) : Optional.empty();
  }

  @Override
  public IList<IEntry<K, V>> entries() {
    return Lists.from(size, i -> entries[(int) i]);
  }

  @Override
  public long size() {
    return size;
  }

  @Override
  public IMap<K, V> forked() {
    throw new IllegalStateException("a LinearMap cannot be efficiently transformed into a forked representation");
  }

  @Override
  public IMap<K, V> linear() {
    return this;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof IMap) {
      return Maps.equals(this, (IMap<K, V>) obj);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return (int) Maps.hash(this);
  }

  @Override
  public String toString() {
    return Maps.toString(this);
  }

  @Override
  public IList<LinearMap<K, V>> partition(int parts) {
    parts = Math.min(parts, size);
    IList<LinearMap<K, V>> list = new LinearList<>(parts);
    if (parts == 0) {
      return list.append(this);
    }

    int partSize = table.length / parts;
    for (int p = 0; p < parts; p++) {
      int start = p * partSize;
      int finish = (p == (parts - 1)) ? table.length : start + partSize;

      LinearMap<K, V> m = new LinearMap<>(finish - start);
      list.append(m);

      for (int i = start; i < finish; i++) {
        long row = table[i];
        if (Row.populated(row)) {
          m.entries[m.size] = rowEntry(row);
          m.constructPut(Row.hash(row), m.size++);
        }
      }
    }

    return list;
  }

  @Override
  public LinearMap<K, V> merge(LinearMap<K, V> o) {
    if (o.size() <= size()) {
      LinearMap<K, V> m = resize((int) (size + o.size()));
      for (int i = 0; i < o.table.length; i++) {
        long row = o.table[i];
        if (Row.populated(row)) {
          m.put(Row.hash(row), o.rowEntry(row));
        }
      }
      return m;
    } else {
      return o.merge(this);
    }
  }

  private void combine(LinearMap<K, ?> m, LinearMap<K, V> result, IntPredicate indexPredicate) {
    for (int i = 0; i < table.length; i++) {
      long row = table[i];
      if (Row.populated(row)) {
        IEntry<K, V> entry = rowEntry(row);
        int entryIndex = m.indexFor(Row.hash(row), entry.key());
        if (indexPredicate.test(entryIndex)) {
          result.entries[result.size] = entry;
          result.constructPut(Row.hash(row), result.size++);
        }
      }
    }
  }

  LinearMap<K, V> difference(LinearMap<K, ?> m) {
    LinearMap<K, V> result = new LinearMap<>(size);
    combine(m, result, i -> i == -1);
    return result;
  }

  LinearMap<K, V> intersection(LinearMap<K, ?> m) {
    LinearMap<K, V> result = new LinearMap<K, V>(Math.min(size, (int) m.size()));
    combine(m, result, i -> i != -1);
    return result;
  }

  /// Utility functions

  private static class Row {

    static final long HASH_MASK = Bits.maskBelow(32);
    static final long ENTRY_INDEX_MASK = Bits.maskBelow(31);
    static final long TOMBSTONE_MASK = 1L << 63;

    static long construct(int hash, int entryIndex) {
      return hash | (entryIndex & ENTRY_INDEX_MASK) << 32;
    }

    static int hash(long row) {
      return (int) (row & HASH_MASK);
    }

    static boolean populated(long row) {
      return (row & HASH_MASK) != NONE && (row & TOMBSTONE_MASK) == 0;
    }

    static int entryIndex(long row) {
      return (int) ((row >>> 32) & ENTRY_INDEX_MASK);
    }

    static boolean tombstone(long row) {
      return (row & TOMBSTONE_MASK) != 0;
    }

    static long addTombstone(long row) {
      return row | TOMBSTONE_MASK;
    }

    static long removeTombstone(long row) {
      return row & ~TOMBSTONE_MASK;
    }
  }

  private IEntry<K, V> rowEntry(long row) {
    return entries[Row.entryIndex(row)];
  }

  private int indexFor(int hash) {
    return hash & indexMask;
  }

  private int nextIndex(int idx) {
    return (idx + 1) & indexMask;
  }

  private int probeDistance(int hash, int index) {
    return (index + table.length - (hash & indexMask)) & indexMask;
  }

  private int keyHash(K key) {
    int hash = hashFn.applyAsInt(key);

    // make sure we don't have too many collisions in the lower bits
    hash ^= (hash >>> 20) ^ (hash >>> 12);
    hash ^= (hash >>> 7) ^ (hash >>> 4);
    return hash == NONE ? FALLBACK : hash;
  }

}