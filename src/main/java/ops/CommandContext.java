package ops;


import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class CommandContext
{
  Map<String, Object> _vars;
  List<MemoryElement> _elements;
  OPS _ops;
  Rule _rule;

  public CommandContext(OPS ops, Rule rule, List<MemoryElement> elements, Map<String, Object> vars)
  {
    _ops = ops;
    _elements = elements;
    _vars = vars;
    _rule = rule;
  }

  public void halt()
  {
    _ops.halt();
  }

  public void remove(int idx)
  {
    if (idx >= _elements.size())
    {
      throw new IllegalArgumentException(String.format("idx %d > match set", idx));
    }
    MemoryElement element = _elements.get(idx);
    _ops.getWorkingMemory().remove(element);
  }

  public MemoryElement make(MemoryElement element)
  {
    return _ops.getWorkingMemory().make(element);
  }

  public MemoryElement modify(int idx, Map<String, Object> values)
  {
    if (idx > _elements.size())
    {
      throw new IllegalArgumentException(String.format("idx %d > match set", idx));
    }

    MemoryElement element = _elements.get(idx);

    for (String key : values.keySet())
    {
      if (!element.Values.containsKey(key))
      {
        throw new IllegalArgumentException("missing field name in element: " + key);
      }
      element.Values.put(key, values.get(key));
    }

    return element;
  }

  public Map<String, Object> resolveValues(Map<String, Object> values)
  {
    Map<String, Object> resolved = new HashMap<String, Object>(values);

    for (String key : values.keySet())
    {
      Object obj = values.get(key);
      if (!(obj instanceof String)) continue;

      String strVal = (String)obj;
      if (!strVal.startsWith("$")) continue;

      if (!hasVar(strVal))
      {
        throw new IllegalArgumentException("missing var:" + strVal);
      }
      resolved.put(key, getVar(strVal));
    }

    return resolved;
  }

  public Object resolveValue(Object var)
  {
    if (!(var instanceof String)) return var;

    String varName = var.toString();
    if (!varName.startsWith("$")) return varName;

    if (!hasVar(varName))
    {
      throw new IllegalArgumentException("missing var:" + varName);
    }
    return getVar(varName);
  }

  public boolean hasVar(String name)
  {
    return _vars.containsKey(name);
  }

  public Object getVar(String name)
  {
    return _vars.get(name);
  }

  public void setVar(String name, Object val)
  {
    _vars.put(name, val);
  }

  public void make(String msg, Object... args)
  {
    _ops.getWorkingMemory().make(msg, args);
  }
}
