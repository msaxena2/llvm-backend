#!/usr/bin/python3

import yaml
import sys

node_count = 0
node_depth = 0

def count_nodes(data, depth):
  global node_count, node_depth
  node_count += 1
  if node_depth < depth:
    node_depth = depth
  if isinstance(data, dict):
    if "next" in data:
      count_nodes(data["next"], depth+1)
    elif "specializations" in data:
      for case in data["specializations"]:
        count_nodes(case[1], depth+1)
      count_nodes(data["default"], depth+1)
    elif "action" in data: pass
  elif data == "fail" or data == None: pass
  else:
    print(type(data))
    raise AssertionError
  

with open(sys.argv[1], 'r') as stream:
  try:
    doc = yaml.safe_load(stream)
    count_nodes(doc, 1)
    print("Size: " + str(node_count))
    print("Path length: " + str(node_depth))
  except yaml.YAMLError as exc:
    print(exc)