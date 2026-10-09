#!/usr/bin/env python3
"""Regenerate checked-in fixtures from an independent upstream codec (not a second port).
Usage: python3 tests/check_codec.py /path/to/ha-ble-adv
OpenSSL supplies AES for the Python reference without installing dependencies.
"""
import ast
import binascii
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace

root=Path(__file__).resolve().parents[1]
reference=Path(sys.argv[1])/"custom_components/ble_adv/codecs"
namespace={}
exec(compile((reference/"utils.py").read_text(),str(reference/"utils.py"),"exec"),namespace)

class Base:
    _len=24
    _seed_max=0xFFF5
    def _crc16(self,buffer,seed): return binascii.crc_hqx(buffer,seed)
    def header(self,header): self._header=bytes(header); return self

class AES:
    MODE_ECB=1
    @staticmethod
    def new(key,mode):
        def encrypt(buffer):
            return subprocess.run(["openssl","enc","-aes-128-ecb","-K",key.hex(),"-nosalt","-nopad"],
                                  input=buffer,capture_output=True,check=True).stdout
        return SimpleNamespace(encrypt=encrypt)

namespace.update(FanLampEncoder=Base,AES=AES)
tree=ast.parse((reference/"fanlamp.py").read_text())
names={"FanLampEncoderV1Base","FanLampEncoderV1","FanLampEncoderV2"}
selected=[node for node in tree.body if isinstance(node,ast.ClassDef) and node.name in names]
module=ast.Module(body=ast.parse("from __future__ import annotations").body+selected,type_ignores=[])
exec(compile(ast.fix_missing_locations(module),"upstream-fanlamp","exec"),namespace)

vectors=[]
for mode in [1,2,3]:
    encoder=(namespace["FanLampEncoderV1"](0x81,True).header([0x77,0xF8]) if mode==1
             else namespace["FanLampEncoderV2"](0x0100))
    for command in [0x10,0x11,0x28]:
        for uid in [0,1,0x123056,0xFEF0FE]:
            for sequence in [0,1,255]:
                for seed in [1,0x1234,0xFFF5]:
                    conf=SimpleNamespace(id=uid,index=0,tx_count=sequence,seed=seed,
                                         codec_params=[mode==2,[0x30 if mode==2 else 0x10,0x80,0]])
                    cmd=SimpleNamespace(cmd=command,param=0,arg0=0,arg1=0,arg2=0,arg3=0)
                    body=encoder.encrypt(encoder.convert_from_enc(cmd,conf))
                    packet=encoder._header+body
                    vectors.append(f"{mode} {command} {uid} {sequence} {seed} {packet.hex()}")

output=root/"app/src/test/resources/upstream-vectors.txt"
output.parent.mkdir(parents=True,exist_ok=True)
output.write_text("\n".join(vectors)+"\n")
print(f"Wrote {len(vectors)} independent upstream vectors to {output}")
