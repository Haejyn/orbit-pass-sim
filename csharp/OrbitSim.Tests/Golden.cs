using System.Globalization;

namespace OrbitSim.Tests;

/// <summary>
/// Java 시험과 <b>같은 골든 파일</b>을 읽는다. 두 구현이 같은 기준값에 맞아야 차분 시험이 뜻을 갖는다.
/// </summary>
internal static class Golden
{
    /// <summary>수치 허용 오차 — `tools/differential.py` 와 같은 값이어야 한다.</summary>
    public const double Atol = 1e-9;

    public const double Rtol = 1e-12;

    private static readonly Lazy<DirectoryInfo> RepoRootLazy = new(FindRepoRoot);

    public static DirectoryInfo RepoRoot => RepoRootLazy.Value;

    public static IReadOnlyList<string[]> Rows(string fileName)
    {
        string path = Path.Combine(RepoRoot.FullName, "src", "test", "resources", "golden", fileName);
        if (!File.Exists(path))
        {
            throw new FileNotFoundException($"golden file not found: {path}", path);
        }

        return File.ReadAllLines(path).Skip(1)
            .Where(line => line.Length > 0)
            .Select(line => line.Split(','))
            .ToList();
    }

    public static double Num(string s) =>
        string.IsNullOrEmpty(s) ? double.NaN : double.Parse(s, CultureInfo.InvariantCulture);

    /// <summary>NaN·±∞ 는 정확히 같아야 하고, 유한값은 허용 오차로 본다.</summary>
    public static bool Close(double expected, double actual) =>
        double.IsNaN(expected) || double.IsNaN(actual)
            ? double.IsNaN(expected) && double.IsNaN(actual)
            : double.IsInfinity(expected) || double.IsInfinity(actual)
                ? expected.Equals(actual)
                : Math.Abs(expected - actual) <= Atol + (Rtol * Math.Abs(expected));

    private static DirectoryInfo FindRepoRoot()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !File.Exists(Path.Combine(dir.FullName, "docs", "requirements.md")))
        {
            dir = dir.Parent;
        }

        return dir ?? throw new DirectoryNotFoundException("repository root (docs/requirements.md) not found");
    }
}
