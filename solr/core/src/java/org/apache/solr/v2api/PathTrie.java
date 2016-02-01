package org.apache.solr.v2api;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.solr.common.util.StrUtils;

import static java.util.Collections.emptyList;

public class PathTrie<T> {
  Node root = new Node(emptyList(), null);


  public void insert(String path, Map<String, String> replacements, T o) {
    List<String> parts = getParts(path);
    if (parts.isEmpty()) {
      root.obj = o;
      return;
    }

    for (int i = 0; i < parts.size(); i++) {
      String part = parts.get(i);
      if (part.charAt(0) == '$') {
        String replacement = replacements.get(part.substring(1));
        if (replacement == null) {
          throw new RuntimeException(part + " is not provided");
        }
        replacement = replacement.charAt(0) == '/' ? replacement.substring(1) : replacement;
        parts.set(i, replacement);
      }
    }

    root.insert(parts, o);
  }

  public static List<String> getParts(String path) {
    if (path == null || path.isEmpty()) return emptyList();
    List<String> parts = StrUtils.splitSmart(path, '/');
    while(true) {
      if(parts.isEmpty()) break;
      if ("".equals(parts.get(0))) parts.remove(0);
      else break;
    }
    return parts;
  }


  public T lookup(String uri, Map<String, String> parts) {
    return root.lookup(getParts(uri), 0, parts);
  }

  public T lookup(String path, Map<String, String> parts, Set<String> paths) {
    return root.lookup(getParts(path), 0, parts, paths);
  }

  public static String wildCardName(String part) {
    return part.startsWith("{") && part.endsWith("}") ?
        part.substring(1, part.length() - 1) :
        null;

  }

  class Node {
    String name;
    Map<String, Node> children;
    T obj;
    String varName;

    Node(List<String> path, T o) {
      if (path.isEmpty()) {
        obj = o;
        return;
      }
      String part = path.get(0);
      varName = wildCardName(part);
      name = part;
      if (path.isEmpty()) obj = o;
    }


    private synchronized void insert(List<String> path, T o) {
      String part = path.get(0);
      Node matchedChild = null;
      if (children == null) children = new ConcurrentHashMap<>();

      String varName = wildCardName(part);
      String key = varName == null ? part : "";

      matchedChild = children.get(key);
      if (matchedChild == null) {
        children.put(key, matchedChild = new Node(path, o));
      }
      if (varName != null) {
        if (!matchedChild.varName.equals(varName)) {
          throw new RuntimeException("wildcard name must be " + matchedChild.varName);
        }
      }
      path.remove(0);
      if (!path.isEmpty()) {
        matchedChild.insert(path, o);
      } else {
        matchedChild.obj = o;
      }

    }


    void findValidChildren(String path, Set<String> availableSubPaths) {
      if (availableSubPaths == null) return;
      if (children != null) {
        for (Node node : children.values()) {
          if (node.obj != null) {
            String s = path + "/" + node.name;
            availableSubPaths.add(s);
          }
        }

        for (Node node : children.values()) {
          node.findValidChildren(path + "/" + node.name, availableSubPaths);
        }
      }
    }


    public T lookup(List<String> pieces, int i, Map<String, String> parts) {
      return lookup(pieces, i, parts, null);

    }

    public T lookup(List<String> pieces, int i, Map<String, String> parts, Set<String> availableSubPaths) {
      if (varName != null) parts.put(varName, pieces.get(i - 1));
      if (pieces.size() < i + 1) {
        findValidChildren("", availableSubPaths);
        return obj;
      }
      String piece = pieces.get(i);
      if (children == null) return null;
      Node n = children.get(piece);
      if (n == null && !reserved.contains(piece)) n = children.get("");
      if (n == null) return null;
      return n.lookup(pieces, i + 1, parts, availableSubPaths);
    }
  }

  private Set<String> reserved = new HashSet<>();

  {
    reserved.add("_introspect");
  }
}
