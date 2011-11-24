package ops;


public interface Command
{
  void exec(CommandContext context, Object[] args);
}
