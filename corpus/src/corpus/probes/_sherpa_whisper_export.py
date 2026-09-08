"""Whisper -> sherpa-onnx ONNX export, adapted from the official k2-fsa/sherpa-onnx script.

Vendored and adapted from
https://github.com/k2-fsa/sherpa-onnx/blob/master/scripts/whisper/export-onnx.py
(commit current as of 2026-09-07), Copyright 2023 Xiaomi Corp. (authors: Fangjun Kuang),
itself adapted from https://github.com/TadaoYamaoka/whisper/blob/main/to_onnx.py.

The only change from the upstream script: ``main()``'s CLI/argparse entry point (which loads
a named model from disk via ``load_model()``) is replaced by :func:`run_export`, which accepts
an in-memory ``whisper.model.Whisper`` instance — the ``merge_and_unload()`` result of a peft
LoRA fine-tune, in R1's case — and writes the same tokens/encoder/decoder ONNX artifacts the
upstream CLI would. The export classes (``AudioEncoderTensorCache``, ``TextDecoderTensorCache``,
etc.), the ``AudioEncoder.forward`` monkeypatch, and the int8 quantization calls are unmodified.

Heavy imports (torch, onnx, onnxruntime, openai-whisper) are all module-level here by design —
this module is only ever imported lazily, inside :class:`RealSherpaOnnxExporter.export`, from
``corpus.probes.lora_export``, so environments without the real toolchain never pay for it.
"""
from __future__ import annotations

import os
from pathlib import Path

import onnx
import torch
import torch.nn.functional as F
import whisper
from onnxruntime.quantization import QuantType, quantize_dynamic
from torch import Tensor, nn
from whisper.model import (
    AudioEncoder,
    MultiHeadAttention,
    ResidualAttentionBlock,
    TextDecoder,
    disable_sdpa,
)


def add_meta_data(filename: str, meta_data: dict) -> None:
    model = onnx.load(filename)
    while len(model.metadata_props):
        model.metadata_props.pop()
    for key, value in meta_data.items():
        meta = model.metadata_props.add()
        meta.key = key
        meta.value = str(value)
    if "large" in filename or "turbo" in filename:
        external_filename = filename.split(".onnx")[0]
        onnx.save(
            model,
            filename,
            save_as_external_data=True,
            all_tensors_to_one_file=True,
            location=external_filename + ".weights",
        )
    else:
        onnx.save(model, filename)


def _modified_audio_encoder_forward(self: AudioEncoder, x: torch.Tensor):
    x = F.gelu(self.conv1(x))
    x = F.gelu(self.conv2(x))
    x = x.permute(0, 2, 1)
    assert x.shape[2] == self.positional_embedding.shape[1], (x.shape, self.positional_embedding.shape)
    assert x.shape[1] == self.positional_embedding.shape[0], (x.shape, self.positional_embedding.shape)
    x = (x + self.positional_embedding[: x.shape[1]]).to(x.dtype)
    for block in self.blocks:
        x = block(x)
    x = self.ln_post(x)
    return x


AudioEncoder.forward = _modified_audio_encoder_forward


class AudioEncoderTensorCache(nn.Module):
    def __init__(self, inAudioEncoder: AudioEncoder, inTextDecoder: TextDecoder):
        super().__init__()
        self.audioEncoder = inAudioEncoder
        self.textDecoder = inTextDecoder

    def forward(self, x: Tensor):
        audio_features = self.audioEncoder(x)
        n_layer_cross_k_list = []
        n_layer_cross_v_list = []
        for block in self.textDecoder.blocks:
            n_layer_cross_k_list.append(block.cross_attn.key(audio_features))
            n_layer_cross_v_list.append(block.cross_attn.value(audio_features))
        return torch.stack(n_layer_cross_k_list), torch.stack(n_layer_cross_v_list)


class MultiHeadAttentionCross(nn.Module):
    def __init__(self, inMultiHeadAttention: MultiHeadAttention):
        super().__init__()
        self.multiHeadAttention = inMultiHeadAttention

    def forward(self, x: Tensor, k: Tensor, v: Tensor, mask: Tensor | None = None):
        q = self.multiHeadAttention.query(x)
        wv, _qk = self.multiHeadAttention.qkv_attention(q, k, v, mask)
        return self.multiHeadAttention.out(wv)


class MultiHeadAttentionSelf(nn.Module):
    def __init__(self, inMultiHeadAttention: MultiHeadAttention):
        super().__init__()
        self.multiHeadAttention = inMultiHeadAttention

    def forward(self, x: Tensor, k_cache: Tensor, v_cache: Tensor, mask: Tensor):
        q = self.multiHeadAttention.query(x)
        k = self.multiHeadAttention.key(x)
        v = self.multiHeadAttention.value(x)
        k_cache[:, -k.shape[1]:, :] = k
        v_cache[:, -v.shape[1]:, :] = v
        wv, _qk = self.multiHeadAttention.qkv_attention(q, k_cache, v_cache, mask)
        return self.multiHeadAttention.out(wv), k_cache, v_cache


class ResidualAttentionBlockTensorCache(nn.Module):
    def __init__(self, inResidualAttentionBlock: ResidualAttentionBlock):
        super().__init__()
        self.originalBlock = inResidualAttentionBlock
        self.attn = MultiHeadAttentionSelf(inResidualAttentionBlock.attn)
        self.cross_attn = (
            MultiHeadAttentionCross(inResidualAttentionBlock.cross_attn)
            if inResidualAttentionBlock.cross_attn
            else None
        )

    def forward(self, x: Tensor, self_k_cache: Tensor, self_v_cache: Tensor, cross_k: Tensor, cross_v: Tensor, mask: Tensor):
        self_attn_x, self_k_cache_updated, self_v_cache_updated = self.attn(
            self.originalBlock.attn_ln(x), self_k_cache, self_v_cache, mask=mask
        )
        x = x + self_attn_x
        if self.cross_attn:
            x = x + self.cross_attn(self.originalBlock.cross_attn_ln(x), cross_k, cross_v)
        x = x + self.originalBlock.mlp(self.originalBlock.mlp_ln(x))
        return x, self_k_cache_updated, self_v_cache_updated


class TextDecoderTensorCache(nn.Module):
    def __init__(self, inTextDecoder: TextDecoder, in_n_ctx: int):
        super().__init__()
        self.textDecoder = inTextDecoder
        self.n_ctx = in_n_ctx
        self.blocks = []
        for orginal_block in self.textDecoder.blocks:
            self.blocks.append(ResidualAttentionBlockTensorCache(orginal_block))

    def forward(self, tokens: Tensor, n_layer_self_k_cache: Tensor, n_layer_self_v_cache: Tensor, n_layer_cross_k: Tensor, n_layer_cross_v: Tensor, offset: Tensor):
        x = (
            self.textDecoder.token_embedding(tokens)
            + self.textDecoder.positional_embedding[offset[0]: offset[0] + tokens.shape[-1]]
        )
        x = x.to(n_layer_cross_k[0].dtype)
        for i, block in enumerate(self.blocks):
            self_k_cache = n_layer_self_k_cache[i, :, : offset[0] + tokens.shape[-1], :]
            self_v_cache = n_layer_self_v_cache[i, :, : offset[0] + tokens.shape[-1], :]
            x, self_k_cache, self_v_cache = block(
                x, self_k_cache=self_k_cache, self_v_cache=self_v_cache,
                cross_k=n_layer_cross_k[i], cross_v=n_layer_cross_v[i], mask=self.textDecoder.mask,
            )
            n_layer_self_k_cache[i, :, : offset[0] + tokens.shape[-1], :] = self_k_cache
            n_layer_self_v_cache[i, :, : offset[0] + tokens.shape[-1], :] = self_v_cache
        x = self.textDecoder.ln(x)
        logits = (
            torch.matmul(self.textDecoder.token_embedding.weight.to(x.dtype), x.permute(0, 2, 1))
            .permute(0, 2, 1)
            .float()
        )
        return logits, n_layer_self_k_cache, n_layer_self_v_cache


def convert_tokens(name: str, model) -> None:
    whisper_dir = Path(whisper.__file__).parent
    multilingual = model.is_multilingual
    tokenizer = whisper_dir / "assets" / (multilingual and "multilingual.tiktoken" or "gpt2.tiktoken")
    if not tokenizer.is_file():
        raise ValueError(f"Cannot find {tokenizer}")
    with open(tokenizer, "r") as f:
        contents = f.read()
        tokens = {token: int(rank) for token, rank in (line.split() for line in contents.splitlines() if line)}
    with open(f"{name}-tokens.txt", "w") as f:
        f.writelines(f"{t} {i}\n" for t, i in tokens.items())


@torch.no_grad()
def run_export(model, name: str, out_dir: str) -> str:
    """Export an in-memory whisper.model.Whisper (e.g. a merge_and_unload() result) to the
    tokens/encoder/decoder ONNX files sherpa-onnx's OfflineRecognizer.from_whisper expects.

    Mirrors the body of the upstream script's ``main()`` exactly, substituting the in-memory
    ``model`` for its ``load_model(args.model)`` call. Returns ``out_dir``.
    """
    os.makedirs(out_dir, exist_ok=True)
    cwd = os.getcwd()
    os.chdir(out_dir)
    sdpa_ctx = disable_sdpa()
    sdpa_ctx.__enter__()
    try:
        opset_version = 17

        convert_tokens(name=name, model=model)

        tokenizer = whisper.tokenizer.get_tokenizer(model.is_multilingual, num_languages=model.num_languages)

        model.eval()
        audio = whisper.pad_or_trim(torch.rand(16000 * 2))
        assert audio.shape == (16000 * 30,), audio.shape

        n_mels = model.dims.n_mels
        mel = whisper.log_mel_spectrogram(audio, n_mels=n_mels).to(model.device).unsqueeze(0)
        batch_size = 1
        assert mel.shape == (batch_size, n_mels, 30 * 100), mel.shape

        encoder = AudioEncoderTensorCache(model.encoder, model.decoder)
        n_layer_cross_k, n_layer_cross_v = encoder(mel)

        encoder_filename = f"{name}-encoder.onnx"
        torch.onnx.export(
            encoder, mel, encoder_filename, opset_version=opset_version, dynamo=False,
            input_names=["mel"], output_names=["n_layer_cross_k", "n_layer_cross_v"],
            dynamic_axes={
                "mel": {0: "n_audio", 2: "T"},
                "n_layer_cross_k": {1: "n_audio", 2: "T"},
                "n_layer_cross_v": {1: "n_audio", 2: "T"},
            },
        )

        encoder_meta_data = {
            "model_type": f"whisper-{name}", "version": "1", "maintainer": "k2-fsa",
            "n_mels": model.dims.n_mels, "n_audio_ctx": model.dims.n_audio_ctx,
            "n_audio_state": model.dims.n_audio_state, "n_audio_head": model.dims.n_audio_head,
            "n_audio_layer": model.dims.n_audio_layer, "n_vocab": model.dims.n_vocab,
            "n_text_ctx": model.dims.n_text_ctx, "n_text_state": model.dims.n_text_state,
            "n_text_head": model.dims.n_text_head, "n_text_layer": model.dims.n_text_layer,
            "sot_sequence": ",".join(map(str, tokenizer.sot_sequence)),
            "all_language_tokens": ",".join(map(str, tokenizer.all_language_tokens)),
            "all_language_codes": ",".join(tokenizer.all_language_codes),
            "sot": tokenizer.sot, "sot_index": tokenizer.sot_sequence.index(tokenizer.sot),
            "eot": tokenizer.eot, "blank_id": tokenizer.encode(" ")[0],
            "is_multilingual": int(model.is_multilingual), "no_speech": tokenizer.no_speech,
            "non_speech_tokens": ",".join(map(str, tokenizer.non_speech_tokens)),
            "transcribe": tokenizer.transcribe, "translate": tokenizer.translate,
            "sot_prev": tokenizer.sot_prev, "sot_lm": tokenizer.sot_lm,
            "no_timestamps": tokenizer.no_timestamps,
        }
        add_meta_data(filename=encoder_filename, meta_data=encoder_meta_data)

        n_audio = mel.shape[0]
        tokens = torch.tensor([[tokenizer.sot, tokenizer.sot, tokenizer.sot]] * n_audio).to(mel.device)
        decoder = TextDecoderTensorCache(model.decoder, model.dims.n_text_ctx)
        n_layer_self_k_cache = torch.zeros(
            (len(model.decoder.blocks), n_audio, model.dims.n_text_ctx, model.dims.n_text_state), device=mel.device,
        )
        n_layer_self_v_cache = torch.zeros(
            (len(model.decoder.blocks), n_audio, model.dims.n_text_ctx, model.dims.n_text_state), device=mel.device,
        )
        offset = torch.zeros(1, dtype=torch.int64).to(mel.device)
        _, n_layer_self_k_cache, n_layer_self_v_cache = decoder(
            tokens, n_layer_self_k_cache, n_layer_self_v_cache, n_layer_cross_k, n_layer_cross_v, offset,
        )

        offset = torch.tensor([tokens.shape[1]], dtype=torch.int64).to(mel.device)
        tokens = torch.tensor([[tokenizer.sot]] * n_audio).to(mel.device)
        decoder(tokens, n_layer_self_k_cache, n_layer_self_v_cache, n_layer_cross_k, n_layer_cross_v, offset)

        decoder_filename = f"{name}-decoder.onnx"
        torch.onnx.export(
            decoder,
            (tokens, n_layer_self_k_cache, n_layer_self_v_cache, n_layer_cross_k, n_layer_cross_v, offset),
            decoder_filename, opset_version=opset_version, dynamo=False,
            input_names=["tokens", "in_n_layer_self_k_cache", "in_n_layer_self_v_cache", "n_layer_cross_k", "n_layer_cross_v", "offset"],
            output_names=["logits", "out_n_layer_self_k_cache", "out_n_layer_self_v_cache"],
            dynamic_axes={
                "tokens": {0: "n_audio", 1: "n_tokens"},
                "in_n_layer_self_k_cache": {1: "n_audio"},
                "in_n_layer_self_v_cache": {1: "n_audio"},
                "n_layer_cross_k": {1: "n_audio", 2: "T"},
                "n_layer_cross_v": {1: "n_audio", 2: "T"},
            },
        )

        encoder_filename_int8 = f"{name}-encoder.int8.onnx"
        quantize_dynamic(
            model_input=encoder_filename, model_output=encoder_filename_int8,
            op_types_to_quantize=["MatMul"], weight_type=QuantType.QInt8,
        )
        decoder_filename_int8 = f"{name}-decoder.int8.onnx"
        quantize_dynamic(
            model_input=decoder_filename, model_output=decoder_filename_int8,
            op_types_to_quantize=["MatMul"], weight_type=QuantType.QInt8,
        )
    finally:
        sdpa_ctx.__exit__(None, None, None)
        os.chdir(cwd)
    return out_dir
