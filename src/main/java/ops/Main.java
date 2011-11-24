package ops;


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

    OPS ops = OpsFactory.create(OpsFactory.getDefaultRegistry(), args[0]);
    ops.run();
  }
}
