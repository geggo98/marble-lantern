#!/bin/zsh
# usage: check-fma.sh <simd_net object> <simd_net_nofma object>
# Proves in the object code (WP10) that the FMA variant of the SIMD forward pass contains fused multiply-add instructions
# (arm64: fmla/fmadd/fmsub, x86-64: vfmadd*) and that the no-FMA variant contains none. Both counts are printed; the check
# fails if the first is 0 (the per-file flag for simd_net did not take effect: the "FMA" row would be a lie), if the second
# is above 0, or if an object has no instructions at all (a disassembly that sees nothing proves nothing).
# Exit 0 ok, 1 proof failed, 77 no disassembler (ctest: skipped).
setopt err_exit pipe_fail no_unset
fma_obj=${1:?usage: check-fma.sh <simd_net.o> <simd_net_nofma.o>}
nofma_obj=${2:?usage: check-fma.sh <simd_net.o> <simd_net_nofma.o>}
for f in $fma_obj $nofma_obj; do [[ -f $f ]] || { print -u2 "check-fma: no such file: $f"; exit 1 }; done

dis=
for c in objdump llvm-objdump; do command -v $c >/dev/null 2>&1 && { dis=$c; break } done
[[ -n $dis ]] || { print -u2 "check-fma: no objdump on PATH"; exit 77 }

count() {   # $1 = object; prints "<instructions> <fused>"
  $dis -d $1 | perl -ne '
    next unless /^\s*[0-9a-f]+:\s+(?:[0-9a-f]{2}\s+)*(?:[0-9a-f]{8}\s+)?(?<m>[a-z][a-z0-9]*)(?:\.[a-z0-9.]*)?\b/;
    $n++; $f++ if $+{m} =~ /^(?:fmla|fmls|fmadd|fmsub|fnmadd|fnmsub|vfn?m(?:add|sub)\w*)$/;
    END { printf "%d %d\n", $n // 0, $f // 0 }'
}
read -r fma_n fma_f <<< "$(count $fma_obj)"
read -r nofma_n nofma_f <<< "$(count $nofma_obj)"
print "simd_net (FMA):        $fma_n instructions, $fma_f fused multiply-add"
print "simd_net_nofma:        $nofma_n instructions, $nofma_f fused multiply-add"
(( fma_n > 100 && nofma_n > 100 )) || { print -u2 "check-fma: the disassembly shows too few instructions"; exit 1 }
(( fma_f > 0 )) || { print -u2 "check-fma: the FMA variant has no fused instruction"; exit 1 }
(( nofma_f == 0 )) || { print -u2 "check-fma: the no-FMA variant has $nofma_f fused instructions"; exit 1 }
print "check-fma: ok"
