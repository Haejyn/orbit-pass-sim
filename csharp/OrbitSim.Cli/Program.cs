using System.Text;
using OrbitSim;

// 차분 시험용 CLI — 입력 CSV 의 사례를 계산해 출력 CSV 로 쓴다.
// Java `orbitsim.cli.DiffCli` 와 같은 입출력 계약이어야 한다 (tools/differential.py 가 둘을 대조한다).
//
//   dotnet OrbitSim.Cli.dll <input.csv> <output.csv>
if (args.Length != 2)
{
    Console.Error.WriteLine("usage: OrbitSim.Cli <input.csv> <output.csv>");
    return 2;
}

string[] lines = File.ReadAllLines(args[0], Encoding.UTF8);
var sb = new StringBuilder();
sb.Append("case,status,o1,o2,o3,o4,o5,o6\n");
for (int i = 1; i < lines.Length; i++)
{
    if (lines[i].Length == 0)
    {
        continue;
    }

    string[] fields = lines[i].Split(',');
    DiffCase c = DifferentialCases.ParseCase(fields);
    DiffResult r = DifferentialCases.Evaluate(c);
    sb.Append(c.Id).Append(',').Append(r.Status);
    for (int k = 0; k < DifferentialCases.MaxOutputs; k++)
    {
        sb.Append(',');
        if (k < r.Values.Count)
        {
            sb.Append(DifferentialCases.Format(r.Values[k]));
        }
    }

    sb.Append('\n');
}

// 개행은 LF 로 고정한다 — OS 가 달라도 같은 파일이 나와야 한다 (.gitattributes 와 같은 이유).
File.WriteAllText(args[1], sb.ToString(), new UTF8Encoding(false));
return 0;
