package ops;


import ops.commands.bind;
import ops.commands.halt;
import ops.commands.make;
import ops.commands.modify;
import ops.commands.remove;
import ops.commands.write;
import java.util.HashMap;
import java.util.Map;


public class Main
{

  public static void main(String[] args)
      throws Exception
  {
    if (args.length <= 0)
    {
      System.out.println("usage: <rules.json>");
      return;
    }

    Map<String, Command> registry = new HashMap<String, Command>();
    registry.put("remove", new remove());
    registry.put("write", new write());
    registry.put("bind", new bind());
    registry.put("halt", new halt());
    registry.put("make", new make());
    registry.put("modify", new modify());

    OPS ops = OpsFactory.create(registry, args[0]);
    ops.run();
  }
}
