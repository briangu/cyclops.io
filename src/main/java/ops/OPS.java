package ops;


import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;


public class OPS
{
  private HashMap<String, List<MemoryElement>> _wm = new HashMap<String, List<MemoryElement>>();
  private List<Rule> _rules = new ArrayList<Rule>();
  private List<PreparedRule> _preparedRules = new ArrayList<PreparedRule>();
  private Map<String, MemoryElement> _templates = new HashMap<String, MemoryElement>();
  private Rule _lastRuleFired = null;

  ExecutorService _productionPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  ExecutorService _rulePool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

  private ConcurrentLinkedQueue<MemoryElement> _memoryInQueue = new ConcurrentLinkedQueue<MemoryElement>();

  private boolean _halt = false;
  private boolean _sortRulesBySpecificity = false;

  public void reset()
  {
    _halt = false;
    _rules.clear();
    _templates.clear();
    _wm.clear();
  }

  public void shutdown()
  {
    _productionPool.shutdown();
    _rulePool.shutdown();
  }

  public void halt()
  {
    _halt = true;
  }

  public void literalize(MemoryElement template)
  {
    _templates.put(template.Type, template);
  }

  public void insert(MemoryElement element)
  {
    if (!_wm.containsKey(element.Type))
    {
      _wm.put(element.Type, new ArrayList<MemoryElement>());
    }
    _wm.get(element.Type).add(element);
  }

  public void remove(MemoryElement element)
  {
    List<MemoryElement> wme = _wm.get(element.Type);
    if (wme == null) return;
    wme.remove(element);
  }

  public MemoryElement make(String type, Object... args)
  {
    return make(new MemoryElement(type, args));
  }

  public MemoryElement make(MemoryElement element)
  {
    MemoryElement newElement = null;

    try
    {
      if (!_templates.containsKey(element.Type))
      {
        throw new IllegalArgumentException(String.format("memory element type %s not literalized", element.Type));
      }

      newElement = _templates.get(element.Type).make(element.Values);

      _memoryInQueue.add(newElement);

      return newElement;
    }
    catch (Exception e)
    {
      e.printStackTrace();
    }

    return newElement;
  }

  public void drainInMemoryQueue()
  {
    while(!_memoryInQueue.isEmpty())
    {
      insert(_memoryInQueue.remove());
    }
  }

  public void run()
  {
    run(Integer.MAX_VALUE);
  }

  public void run(int steps)
  {
    _halt = false;

    while (steps-- > 0 && !_halt)
    {
      drainInMemoryQueue();

      Match match = match(_rulePool, _rules, _lastRuleFired, _wm);
      if (match == null)
      {
        if (!_memoryInQueue.isEmpty())
        {
          continue;
        }

        break;
      }

      _lastRuleFired = match.Rule;

      final CommandContext context = new CommandContext(this, match.Rule, match.Elements, match.Vars);

      for (final ProductionSpec production : match.Rule.Productions)
      {
        final Object[] args = new Object[production.Params.length];

        for (int i = 0; i < args.length; i++)
        {
          args[i] = context.resolveValue(production.Params[i]);
        }

        try
        {
          if (production.Command instanceof AsyncCommand)
          {
            OpsRunnable opsRunnable =
                new OpsRunnable(
                  match.Rule.Name,
                  new Runnable()
                  {
                    @Override
                    public void run()
                    {
                      try
                      {
                        production.Command.exec(context, args);
                      }
                      catch (Exception e)
                      {
                        e.printStackTrace();
                      }
                    }
                  });

            make(new MemoryElement("async_task", "phase", "start", "id", opsRunnable.Id, "name", match.Rule.Name));

            _productionPool.submit(opsRunnable);
          }
          else
          {
            production.Command.exec(context, args);
          }
        }
        catch (Exception e)
        {
          System.err.println(production.toString());
          e.printStackTrace();
        }
      }

//      if (_debug) dumpWorkingMemory();
    }

    _halt = true;
  }

  private void dumpWorkingMemory()
  {
    for (List<MemoryElement> wme : _wm.values())
    {
      for (MemoryElement me : wme)
      {
        System.out.println(me.toString());
      }
    }
  }

  public void addRules(List<Rule> rules)
  {
    _rules.addAll(rules);
    prepareQueries();
  }

  public void addRule(Rule rule)
  {
    _rules.add(rule);
    prepareQueries();
  }

  private void prepareQueries()
  {
    _preparedRules.clear();

    for (Rule rule : _rules)
    {
      PreparedRule preparedRule = new PreparedRule();
      preparedRule.Rule = rule;
      preparedRule.Specificity = computeSpecificity(rule);
      _preparedRules.add(preparedRule);
    }

    if (_sortRulesBySpecificity)
    {
      Collections.sort(_preparedRules, new Comparator<PreparedRule>()
      {
        @Override
        public int compare(PreparedRule preparedRule, PreparedRule preparedRule1)
        {
          return preparedRule1.Specificity.compareTo(preparedRule.Specificity);
        }
      });
    }

    _rules.clear();
    for (PreparedRule preparedRule : _preparedRules)
    {
      _rules.add(preparedRule.Rule);
    }
  }

  private Integer computeSpecificity(Rule rule)
  {
    Integer specificity = 0;

    for (QueryElement element : rule.Query)
    {
      Integer elementSpecificity = 0;

      for (QueryPair queryPair : element.QueryPairs)
      {
        if (!(queryPair.Value instanceof String)) continue;
        String strVal = (String) queryPair.Value;
        if (!strVal.startsWith("$")) continue;
        elementSpecificity++;
      }

      specificity += elementSpecificity;
    }

    return specificity;
  }

  public void promote(Rule rule)
  {
    int idx = _rules.indexOf(rule);
    if (idx < 0)
    {
      throw new IllegalArgumentException("rule not found in rule set: " + rule.Name);
    }
    if (idx != 0)
    {
      _rules.remove(rule);
      _rules.add(0, rule);
    }
  }

  private class OpsRunnable implements Runnable
  {
    public String Id = UUID.randomUUID().toString();
    public Runnable Runnable;
    public String Name;
    public OpsRunnable(String name, Runnable runnable)
    {
      Name = name;
      Runnable = runnable;
    }

    @Override
    public void run()
    {
      try
      {
        Runnable.run();
      }
      catch (Exception e)
      {
        e.printStackTrace();
      }
      finally
      {
        make(new MemoryElement("async_task", "phase", "stop", "id", Id, "name", Name));
      }
    }
  }

  private class PreparedRule
  {
    Rule Rule;
    Integer Specificity;
  }

  private Match match()
  {
    Match match = null;

    for (Rule rule : _rules)
    {
      List<MemoryElement> elements = new ArrayList<MemoryElement>();
      Map<String, Object> vars = new HashMap<String, Object>();

      for (QueryElement qe : rule.Query)
      {
        List<MemoryElement> wme = _wm.get(qe.Type);

        boolean haveMatch = false;

        if (wme != null)
        {
          for (MemoryElement me : wme)
          {
            if (elements.contains(me)) continue;
            Map<String, Object> tmpVars = new HashMap<String, Object>(vars);
            haveMatch = compare(qe, me, tmpVars);
            if (haveMatch)
            {
              vars = tmpVars;
              elements.add(me);
              break;
            }
          }
        }

        if (!haveMatch)
        {
          break;
        }
      }

      if (elements.size() == rule.Query.size())
      {
        match = new Match(rule, elements, vars);
        break;
      }
    }

    return match;
  }

  private static Match match(Rule rule, HashMap<String, List<MemoryElement>> wm)
  {
    Match match = null;

    List<MemoryElement> elements = new ArrayList<MemoryElement>();
    Map<String, Object> vars = new HashMap<String, Object>();

    for (QueryElement qe : rule.Query)
    {
      List<MemoryElement> wme = wm.get(qe.Type);

      boolean haveMatch = false;

      if (wme != null)
      {
        for (MemoryElement me : wme)
        {
          if (elements.contains(me)) continue;
          Map<String, Object> tmpVars = new HashMap<String, Object>(vars);
          haveMatch = compare(qe, me, tmpVars);
          if (haveMatch)
          {
            vars = tmpVars;
            elements.add(me);
            break;
          }
        }
      }

      if (!haveMatch)
      {
        break;
      }
    }

    if (elements.size() == rule.Query.size())
    {
      match = new Match(rule, elements, vars);
    }

    return match;
  }

  private Match match(ExecutorService e, List<Rule> rules, Rule lastRuleFired, final HashMap<String, List<MemoryElement>> wm)
  {
    CompletionService<Match> ecs = new ExecutorCompletionService<Match>(e);

    int n = rules.size();

    List<Future<Match>> futures = new ArrayList<Future<Match>>(n);

    List<Match> hits = new ArrayList<Match>();

    try
    {
      for (final Rule rule : rules)
      {
        futures.add(ecs.submit(new Callable<Match>()
        {
          @Override
          public Match call() throws Exception
          {
            return match(rule, wm);
          }
        }));
      }

      for (int i = 0; i < n; ++i)
      {
        try
        {
          Match r = ecs.take().get();
          if (r != null)
          {
            hits.add(r);
          }
        }
        catch (ExecutionException ignore)
        {
        }
        catch (InterruptedException e1)
        {
          break;
        }
      }
    }
    finally
    {
      for (Future<Match> f : futures)
      {
        f.cancel(true);
      }
    }

    if (hits.size() == 0) return null;

    // RESOLVE CONFLICT
    if (hits.size() == 1) return hits.get(0);
    hits.remove(lastRuleFired);
    return hits.get(0);
  }

  private static boolean compare(QueryElement qe, MemoryElement me, Map<String, Object> vars)
  {
    if (!(me.Type.equals(qe.Type))) return false;

    for (QueryPair qp : qe.QueryPairs)
    {
      Object val = me.Values.containsKey(qp.Key) ? me.Values.get(qp.Key) : null;

      if (qp.Value == null)
      {
        if (val == null)
        {
          // match
        }
        else
        {
          return false;
        }
      }
      else
      {
        if (qp.Value instanceof String)
        {
          String strQpVal = (String)qp.Value;
          if (strQpVal.startsWith("$"))
          {
            if (vars.containsKey(strQpVal))
            {
              if (!vars.get(strQpVal).equals(val))
              {
                return false;
              }
            }
            else
            {
              // variable matches everything
              vars.put(strQpVal, val);
            }
          }
          else
          {
            if (!strQpVal.equals(val))
            {
              return false;
            }
          }
        }
        else
        {
          if (!qp.Value.equals(val))
          {
            return false;
          }
        }
      }
    }

    return true;
  }

  private static class Match
  {
    public Rule Rule;
    public List<MemoryElement> Elements;
    public Map<String, Object> Vars;

    public Match(Rule rule, List<MemoryElement> elements, Map<String, Object> vars)
    {
      Rule = rule;
      Elements = elements;
      Vars = vars;
    }
  }
}
