package ops.commands;


import ops.Command;
import ops.CommandContext;


public class promote implements Command
{
  @Override
  public void exec(CommandContext context, Object[] args)
  {
    context.promote();
  }
}
