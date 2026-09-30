using System.Text.Json.Nodes;
using Slate.Core;
using Xunit;

namespace Slate.Core.Tests;

public class AskTests
{
    [Fact]
    public void ParsesAskBlobWithTwoImages()
    {
        var header = new JsonObject
        {
            ["t"] = "ask", ["askId"] = "a1", ["session"] = "s1", ["new"] = false, ["intent"] = "hint", ["title"] = "Calculus",
            ["images"] = new JsonArray(new JsonObject { ["name"] = "screen.jpg", ["size"] = 3 }, new JsonObject { ["name"] = "crop.jpg", ["size"] = 2 }),
        };
        var r = AskRequest.FromBlob(header, new byte[] { 1, 2, 3, 4, 5 })!;
        Assert.Equal("hint", r.Intent);
        Assert.Equal(new byte[] { 1, 2, 3 }, r.Images[0].Data);
        Assert.Equal(("crop.jpg"), r.Images[1].Name);
        Assert.Equal(new byte[] { 4, 5 }, r.Images[1].Data);
    }

    [Fact]
    public void RejectsBadBlobsAndUnsafeNames()
    {
        var tooBig = new JsonObject { ["askId"] = "a", ["images"] = new JsonArray(new JsonObject { ["name"] = "x.jpg", ["size"] = 10 }) };
        Assert.Null(AskRequest.FromBlob(tooBig, new byte[3]));
        Assert.Null(AskRequest.FromBlob(new JsonObject { ["askId"] = "a" }, Array.Empty<byte>()));

        var sneaky = new JsonObject { ["askId"] = "a", ["images"] = new JsonArray(new JsonObject { ["name"] = @"..\..\Windows\evil.exe", ["size"] = 1 }) };
        Assert.Equal("image.jpg", AskRequest.FromBlob(sneaky, new byte[1])!.Images[0].Name); // not an image name
        var pathy = new JsonObject { ["askId"] = "a", ["images"] = new JsonArray(new JsonObject { ["name"] = @"..\..\screen.png", ["size"] = 1 }) };
        Assert.Equal("screen.png", AskRequest.FromBlob(pathy, new byte[1])!.Images[0].Name);
        var dots = new JsonObject { ["askId"] = "a", ["images"] = new JsonArray(new JsonObject { ["name"] = "..", ["size"] = 1 }) };
        Assert.Equal("image.jpg", AskRequest.FromBlob(dots, new byte[1])!.Images[0].Name);
    }

    [Fact]
    public void PromptPointsAtTheSavedImagesAndIntent()
    {
        var r = new AskRequest("a", "", true, "check", "", new[] { ("screen.jpg", new byte[1]), ("crop.jpg", new byte[1]) });
        var p = StudyPrompt.ForClaudeCode(r, new[] { @"C:\S\q1-screen.jpg", @"C:\S\q1-crop.jpg" });
        Assert.StartsWith(StudyPrompt.Intents["check"], p);
        Assert.Contains(@"C:\S\q1-screen.jpg (my screen with my annotations)", p);
        Assert.Contains(@"C:\S\q1-crop.jpg (zoomed crop of what I circled)", p);
    }

    [Fact]
    public void ParsesClaudeCodeStream()
    {
        Assert.Equal(new ClaudeCodeEvent.Started("abc"), ClaudeCodeEvent.Parse("""{"type":"system","subtype":"init","session_id":"abc","tools":[]}"""));
        Assert.Equal(new ClaudeCodeEvent.Text("Hel"), ClaudeCodeEvent.Parse(
            """{"type":"stream_event","parent_tool_use_id":null,"event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}}"""));
        Assert.Null(ClaudeCodeEvent.Parse( // a subagent's text isn't the answer
            """{"type":"stream_event","parent_tool_use_id":"toolu_1","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"x"}}}"""));
        Assert.Equal(new ClaudeCodeEvent.Tool("Read"), ClaudeCodeEvent.Parse(
            """{"type":"stream_event","parent_tool_use_id":null,"event":{"type":"content_block_start","content_block":{"type":"tool_use","name":"Read"}}}"""));
        Assert.Equal(new ClaudeCodeEvent.Finished("Done **well**", "abc", 0.042, false), ClaudeCodeEvent.Parse(
            """{"type":"result","subtype":"success","is_error":false,"result":"Done **well**","session_id":"abc","total_cost_usd":0.042}"""));
        Assert.True(((ClaudeCodeEvent.Finished)ClaudeCodeEvent.Parse("""{"type":"result","subtype":"error_max_turns","session_id":"abc"}""")!).IsError);
        Assert.Null(ClaudeCodeEvent.Parse("not json"));
        Assert.Null(ClaudeCodeEvent.Parse("""{"type":"assistant","message":{}}"""));
    }
}

public class ClaudeCodeRunnerTests
{
    [Fact]
    public void ArgsForNewAndResumedSessions()
    {
        var fresh = ClaudeCodeRunner.Args(true, "u-1", "Slate: Maths", "p.md");
        Assert.Equal(new[] { "--session-id", "u-1", "--name", "Slate: Maths" }, fresh.TakeLast(4));
        Assert.Contains("stream-json", fresh);
        Assert.Contains("dontAsk", fresh);
        Assert.Equal(new[] { "--resume", "u-1" }, ClaudeCodeRunner.Args(false, "u-1", "x", "p.md").TakeLast(2));
    }

    [Fact]
    public async Task StreamsAFakeClaudeCode()
    {
        if (OperatingSystem.IsWindows()) return; // the fake CLI is a shell script
        var dir = Directory.CreateTempSubdirectory("slate-cc").FullName;
        var fake = Path.Combine(dir, "claude");
        // Echoes the prompt it got on stdin back as the answer, in Claude Code's stream-json shape.
        File.WriteAllText(fake, """
            #!/bin/sh
            prompt=$(cat)
            echo '{"type":"system","subtype":"init","session_id":"s-9"}'
            echo '{"type":"stream_event","parent_tool_use_id":null,"event":{"type":"content_block_start","content_block":{"type":"tool_use","name":"Read"}}}'
            echo '{"type":"stream_event","parent_tool_use_id":null,"event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"It is "}}}'
            echo '{"type":"stream_event","parent_tool_use_id":null,"event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"x²"}}}'
            echo "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"It is x² ($(echo "$prompt" | head -c 5))\",\"session_id\":\"s-9\",\"total_cost_usd\":0.01}"
            echo 'warning on stderr' >&2
            """.Replace("\r", ""));
        File.SetUnixFileMode(fake, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);

        var events = new List<ClaudeCodeEvent>();
        var outcome = await ClaudeCodeRunner.RunAsync(fake, dir, new[] { "-p" }, "Hello there", events.Add);
        Assert.Equal("It is x² (Hello)", outcome.Finished!.Result);
        Assert.Equal("s-9", outcome.Finished.SessionId);
        Assert.Equal("It is x²", outcome.StreamedText);
        Assert.Equal("warning on stderr", outcome.Errors);
        Assert.Contains(events, e => e is ClaudeCodeEvent.Tool { Name: "Read" });
        Assert.Equal(0, outcome.ExitCode);
    }
}
