package library;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/// Each method's change, written out: `xClaim` is `x` as its card says the mutant makes it.
class Ends {
  static String firstOfAList(String a, String b) {
    return List.of(a, b).getFirst();
  }

  static String firstOfAListClaim(String a, String b) {
    return List.of(a, b).getLast();
  }

  static String lastOfAnArrayList(String a, String b) {
    return new ArrayList<>(List.of(a, b)).getLast();
  }

  static String lastOfAnArrayListClaim(String a, String b) {
    return new ArrayList<>(List.of(a, b)).getFirst();
  }

  static String firstOfADeque(String a, String b) {
    Deque<String> both = new ArrayDeque<>(List.of(a, b));
    return both.getFirst();
  }

  static String firstOfADequeClaim(String a, String b) {
    Deque<String> both = new ArrayDeque<>(List.of(a, b));
    return both.getLast();
  }

  static String peekLast(String a, String b) {
    Deque<String> both = new ArrayDeque<>(List.of(a, b));
    return both.peekLast();
  }

  static String peekLastClaim(String a, String b) {
    Deque<String> both = new ArrayDeque<>(List.of(a, b));
    return both.peekFirst();
  }

  static String pollFirstOfAnArrayDeque(String a, String b) {
    var both = new ArrayDeque<>(List.of(a, b));
    return both.pollFirst() + both.size();
  }

  static String pollFirstOfAnArrayDequeClaim(String a, String b) {
    var both = new ArrayDeque<>(List.of(a, b));
    return both.pollLast() + both.size();
  }

  static String lastOfALinkedList(String a, String b) {
    var both = new LinkedList<>(List.of(a, b));
    return both.getLast();
  }

  static String lastOfALinkedListClaim(String a, String b) {
    var both = new LinkedList<>(List.of(a, b));
    return both.getFirst();
  }

  static String firstOfASortedSet(String a, String b) {
    SortedSet<String> both = new TreeSet<>(List.of(a, b));
    return both.first();
  }

  static String firstOfASortedSetClaim(String a, String b) {
    SortedSet<String> both = new TreeSet<>(List.of(a, b));
    return both.last();
  }

  static String lastOfATreeSet(String a, String b) {
    var both = new TreeSet<>(List.of(a, b));
    return both.last();
  }

  static String lastOfATreeSetClaim(String a, String b) {
    var both = new TreeSet<>(List.of(a, b));
    return both.first();
  }

  static String pollLastOfANavigableSet(String a, String b) {
    NavigableSet<String> both = new TreeSet<>(List.of(a, b));
    return both.pollLast() + both;
  }

  static String pollLastOfANavigableSetClaim(String a, String b) {
    NavigableSet<String> both = new TreeSet<>(List.of(a, b));
    return both.pollFirst() + both;
  }

  static String firstKey(String a, String b) {
    SortedMap<String, Integer> lengths = new TreeMap<>();
    lengths.put(a, 1);
    lengths.put(b, 2);
    return lengths.firstKey();
  }

  static String firstKeyClaim(String a, String b) {
    SortedMap<String, Integer> lengths = new TreeMap<>();
    lengths.put(a, 1);
    lengths.put(b, 2);
    return lengths.lastKey();
  }

  static String lastEntry(String a, String b) {
    NavigableMap<String, Integer> lengths = new TreeMap<>();
    lengths.put(a, 1);
    lengths.put(b, 2);
    return lengths.lastEntry().toString();
  }

  static String lastEntryClaim(String a, String b) {
    NavigableMap<String, Integer> lengths = new TreeMap<>();
    lengths.put(a, 1);
    lengths.put(b, 2);
    return lengths.firstEntry().toString();
  }

  static String firstEntryInOrderOfInsertion(String a, String b) {
    var lengths = new LinkedHashMap<String, Integer>();
    lengths.put(a, 1);
    lengths.put(b, 2);
    return lengths.firstEntry().toString();
  }

  static String firstEntryInOrderOfInsertionClaim(String a, String b) {
    var lengths = new LinkedHashMap<String, Integer>();
    lengths.put(a, 1);
    lengths.put(b, 2);
    return lengths.lastEntry().toString();
  }
}
