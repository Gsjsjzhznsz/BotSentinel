import cn.botsentinel.*;

public class DbgScore {
    public static void main(String[] args) {
        ScoreEngine eng = new ScoreEngine(null);
        String[] names = {"Iamchine", "23451qwert", "Ciloat77422", "cirawemuLP", "XiaoMing",
                "thMCP2", "Wangxh2002", "lZvXVuRR", "WnyrLuSkBHhWO", "xiaoming123"};
        for (String n : names) {
            ScoreEngine.NameFeatures f = eng.features(n);
            int[] b = eng.breakdown(n, f);
            System.out.printf("%-16s h=%d m=%d e=%d l=%d => %d | H=%.2f vowel=%.2f bigramP=%.2f rep=%.2f%n",
                    n, b[0], b[1], b[2], b[3], b[4], f.entropy, f.vowelRatio, f.markovLogP, f.repeatRatio);
        }
    }
}
