package ops;


import java.util.*;
import java.util.concurrent.*;


public class OPS
{
  private WorkingMemory _wm;

  private List<Rule> _rules = new ArrayList<Rule>();
  private List<PreparedRule> _preparedRules = new ArrayList<PreparedRule>();
  private Rule _lastRuleFired = null;
  private Map<String, String> _asyncTickets = new ConcurrentHashMap<String, String>();

  ExecutorService _productionPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  ExecutorService _rulePool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

  private boolean _halt = false;
  private boolean _sortRulesBySpecificity = false;

  public OPS() {
    this(new WorkingMemory());
  }

  public OPS(WorkingMemory wm) {
    _wm = wm;
  }

  public WorkingMemory getWorkingMemory() {
    return _wm;
  }

  public void setWorkingMemory(WorkingMemory wm) {
    _wm = wm;
  }

  public void reset()
  {
    _halt = false;
    _rules.clear();
    _wm.reset();
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

  public void run()
  {
    run(-1);
  }

  public void run(int steps)
  {
    _halt = false;
    boolean checkSteps = steps > 0;

    while ((!checkSteps || steps-- > 0) && !_halt)
    {
      _wm.drainInMemoryQueue();

      Match match = match(_rules, _lastRuleFired, _wm);
      if (match == null)
      {
        boolean dequedNew = _wm.drainInMemoryQueueBlockable();

        if (!dequedNew && _asyncTickets.size() == 0)
        {
          break;
        }
        continue;
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

            _asyncTickets.put(opsRunnable.Id, match.Rule.Name);

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
    }

    _halt = true;
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
        _asyncTickets.remove(Id);
      }
    }
  }

  private class PreparedRule
  {
    Rule Rule;
    Integer Specificity;
  }

  private static class MatchContext
  {
    public Set<MemoryElement> Elements;
    public Map<String, Object> Vars;
    public Integer QeIdx;
    public Integer MeIdx;

    public MatchContext()
    {
      this(
        new LinkedHashSet<MemoryElement>(),
        new HashMap<String, Object>(),
        0,
        0);
    }

    public MatchContext(
      Set<MemoryElement> element,
      Map<String, Object> vars,
      Integer qeIdx,
      Integer meIdx)
    {
      Elements = element;
      Vars = vars;
      QeIdx = qeIdx;
      MeIdx = meIdx;
    }

    public MatchContext(MatchContext mc)
    {
      this(
        new LinkedHashSet<MemoryElement>(mc.Elements),
        new HashMap<String, Object>(mc.Vars),
        mc.QeIdx,
        mc.MeIdx);
    }
  }

  private static Match match(Rule rule, WorkingMemory wm)
  {
    Match match = null;

    MatchContext mc = new MatchContext();

    Stack<MatchContext> stack = new Stack<MatchContext>();
    stack.push(mc);

    while((mc.QeIdx < rule.Query.size()) && (stack.size() > 0))
    {
      mc = stack.pop();

      QueryElement qe = rule.Query.get(mc.QeIdx);

      List<MemoryElement> wme = wm.get(qe.Type);
      if (wme == null) break;

      boolean haveMatch = false;

      while(mc.MeIdx < wme.size())
      {
        MemoryElement me = wme.get(mc.MeIdx++);

        if (mc.Elements.contains(me)) continue;

        MatchContext tmpMc = new MatchContext(mc);
        haveMatch = compare(qe, me, tmpMc.Vars);
        if (haveMatch)
        {
          stack.push(mc);
          mc = tmpMc;
          mc.MeIdx = 0;
          mc.QeIdx++;
          mc.Elements.add(me);
          stack.push(mc);
          break;
        }
      }
    }

    if (mc.Elements.size() == rule.Query.size())
    {
      match = new Match(rule, new ArrayList<MemoryElement>(mc.Elements), mc.Vars);
    }

    return match;
  }

  private Match match(List<Rule> rules, Rule lastRuleFired, final WorkingMemory wm)
  {
    List<Match> hits = new ArrayList<Match>();

    for (final Rule rule : rules)
    {
      Match m = match(rule, wm);
      if (m != null)
      {
        hits.add(m);
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
