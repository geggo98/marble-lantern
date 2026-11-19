import re, glob, math

cells = {}
dirs = glob.glob('/Users/stefan/tmp/stern-halma/data/s1/s*-b*-race0-ab*') + glob.glob('/Users/stefan/tmp/stern-halma/data/b3/s*-b*-ab*')
for d in dirs:
    try:
        t = open(d + '/result.txt').read().strip().splitlines()[-1]
        open(d + '/DONE')
    except Exception:
        continue
    m = re.match(r'mcts:sims=(\d+),batch=(\d+)(?:,race=\d)? vs ab:(\d+): Elo ([+-]\d+) \+- (\d+).*score ([\d.]+)', t)
    if not m:
        continue
    s, b, n, e, ci, sc = int(m[1]), int(m[2]), int(m[3]), int(m[4]), int(m[5]), float(m[6])
    if (s, b) not in [(50, 1), (200, 1), (200, 4), (800, 1)]:
        continue
    cells[(s, b, n)] = (e, ci, sc)

use = {k: v for k, v in cells.items() if 0.2 <= v[2] <= 0.9}
subs = sorted({(k[0], k[1]) for k in use})
idx = {s: i for i, s in enumerate(subs)}
N = len(subs) + 1
A = [[0.0] * N for _ in range(N)]
B = [0.0] * N
for (s, b, n), (e, ci, sc) in use.items():
    w = 1 / (ci / 1.96) ** 2
    x = [0.0] * N
    x[idx[(s, b)]] = 1
    x[-1] = -math.log2(n)
    for i in range(N):
        B[i] += w * x[i] * e
        for j in range(N):
            A[i][j] += w * x[i] * x[j]
M = [A[i] + [B[i]] for i in range(N)]
for i in range(N):
    p = max(range(i, N), key=lambda r: abs(M[r][i]))
    M[i], M[p] = M[p], M[i]
    for r in range(N):
        if r != i:
            f = M[r][i] / M[i][i]
            M[r] = [a - f * b for a, b in zip(M[r], M[i])]
sol = [M[i][N] / M[i][i] for i in range(N)]
k = sol[-1]
print('cells used', len(use), 'Elo per doubling of ab nodes k=%.1f' % k)
for s, i in idx.items():
    a = sol[i]
    eq = 2 ** (a / k)
    print(s, 'a=%.0f  Elo vs ab:500000 = %+.0f   BEST_SPEC node-equivalent ~ %.2gM' % (a, a - k * math.log2(500000), eq / 1e6))
for kk, (e, ci, sc) in sorted(use.items()):
    pred = sol[idx[(kk[0], kk[1])]] - k * math.log2(kk[2])
    print(kk, e, 'pred %.0f' % pred)
