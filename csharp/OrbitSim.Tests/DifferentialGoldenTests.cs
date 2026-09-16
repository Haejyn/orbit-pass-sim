namespace OrbitSim.Tests;

/// <summary>
/// 차분 시험 — 골든에 든 <b>Java 출력</b>과 C# 계산 결과를 대조한다 (REQ-DIF-01 · 02).
/// 골든은 `tools/differential.py` 가 Java CLI 로 만든다. 여기서 어긋나면 두 구현이 갈린 것이다.
/// </summary>
public class DifferentialGoldenTests
{
    private const int InputColumns = 18;
    private const int MaxOutputs = 6;

    public static TheoryData<string> Cases()
    {
        var data = new TheoryData<string>();
        foreach (string[] row in Golden.Rows("differential_vectors.csv"))
        {
            data.Add(row[0]);
        }

        return data;
    }

    [Fact]
    public void Golden_covers_every_case_kind_and_both_verdicts()
    {
        IReadOnlyList<string[]> rows = Golden.Rows("differential_vectors.csv");
        HashSet<string> kinds = [.. rows.Select(r => r[1])];
        HashSet<string> statuses = [.. rows.Select(r => r[InputColumns])];

        Assert.Equal(6, kinds.Count);
        Assert.Contains("ok", statuses);
        // 거부 경로가 실제로 시험되고 있어야 "같은 이유로 거부한다" 를 말할 수 있다 (REQ-DIF-02)
        Assert.Contains("reject", statuses);
        Assert.True(rows.Count > 400, $"case count too small: {rows.Count}");
    }

    [Theory]
    [MemberData(nameof(Cases))]
    public void Csharp_matches_the_java_reference(string caseId)
    {
        string[] row = Golden.Rows("differential_vectors.csv").Single(r => r[0] == caseId);
        DiffCase c = DifferentialCases.ParseCase(row.Take(InputColumns).ToArray());
        DiffResult actual = DifferentialCases.Evaluate(c);

        string expectedStatus = row[InputColumns];
        Assert.Equal(expectedStatus, actual.Status);
        if (expectedStatus != "ok")
        {
            return;
        }

        for (int k = 0; k < MaxOutputs; k++)
        {
            string cell = row[InputColumns + 1 + k];
            if (cell.Length == 0)
            {
                Assert.True(k >= actual.Values.Count, $"{caseId}: C# produced an extra output o{k + 1}");
                continue;
            }

            double expected = Golden.Num(cell);
            Assert.True(k < actual.Values.Count, $"{caseId}: C# is missing output o{k + 1}");
            Assert.True(Golden.Close(expected, actual.Values[k]),
                $"{caseId} [{c.Kind}] o{k + 1}: java={expected:R} csharp={actual.Values[k]:R}");
        }
    }
}
