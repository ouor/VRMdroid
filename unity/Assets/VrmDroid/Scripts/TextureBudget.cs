using System.Collections.Generic;
using UniGLTF;
using UnityEngine;

namespace VrmDroid
{
    /// <summary>
    /// Shrinks a loaded avatar's textures to fit a phone's GPU memory. VRMs load every texture
    /// uncompressed (RGBA32 with mipmaps): a VRoid model's 2048² body, normal and clothing maps
    /// plus its thumbnail are ~100 MB of GPU memory and bandwidth for a preview that covers part
    /// of a phone screen. Downscaled copies replace the originals in the materials and in
    /// UniGLTF's resource list, which destroys the originals and frees the copies with the avatar.
    /// </summary>
    public static class TextureBudget
    {
        public static void Shrink(RuntimeGltfInstance gltf, int maxSize)
        {
            if (gltf == null) return;
            var replaced = new Dictionary<Texture, Texture>();
            long before = 0, after = 0;
            foreach (var texture in new List<Texture>(gltf.Textures))
            {
                if (!(texture is Texture2D source) || Mathf.Max(source.width, source.height) <= maxSize) continue;
                var small = Downscale(source, maxSize);
                before += (long)source.width * source.height;
                after += (long)small.width * small.height;
                replaced[source] = small;
                gltf.ReplaceResource(source, small);
            }
            if (replaced.Count == 0) return;

            foreach (var renderer in gltf.GetComponentsInChildren<Renderer>(true))
            {
                foreach (var material in renderer.sharedMaterials)
                {
                    if (material == null) continue;
                    foreach (var property in material.GetTexturePropertyNameIDs())
                    {
                        var current = material.GetTexture(property);
                        // Destroyed originals compare equal to null, so look them up by reference.
                        if ((object)current != null && replaced.TryGetValue(current, out var small)) material.SetTexture(property, small);
                    }
                }
            }
            // RGBA32 with a full mip chain is ~5.3 bytes per base pixel.
            Debug.Log($"[VrmDroid] Shrunk {replaced.Count} textures to {maxSize}px: ~{before * 16 / 3 >> 20} MB -> ~{after * 16 / 3 >> 20} MB");
        }

        /// <summary>
        /// A box-filtered copy at most <paramref name="maxSize"/> on its longer side, drawn on the GPU
        /// and read back once, so it lives as an ordinary texture that survives the app going to the
        /// background (render texture contents may not).
        /// </summary>
        static Texture2D Downscale(Texture2D source, int maxSize)
        {
            var scale = (float)maxSize / Mathf.Max(source.width, source.height);
            var width = Mathf.Max(1, Mathf.RoundToInt(source.width * scale));
            var height = Mathf.Max(1, Mathf.RoundToInt(source.height * scale));
            var linear = !source.isDataSRGB; // normal maps and masks stay linear
            var readWrite = linear ? RenderTextureReadWrite.Linear : RenderTextureReadWrite.sRGB;

            var rt = RenderTexture.GetTemporary(width, height, 0, RenderTextureFormat.ARGB32, readWrite);
            var previous = RenderTexture.active;
            Graphics.Blit(source, rt);
            RenderTexture.active = rt;
            var small = new Texture2D(width, height, TextureFormat.RGBA32, source.mipmapCount > 1, linear)
            {
                name = source.name,
                wrapModeU = source.wrapModeU,
                wrapModeV = source.wrapModeV,
                filterMode = source.filterMode,
                anisoLevel = source.anisoLevel,
            };
            small.ReadPixels(new Rect(0, 0, width, height), 0, 0, false);
            small.Apply(updateMipmaps: true, makeNoLongerReadable: true);
            RenderTexture.active = previous;
            RenderTexture.ReleaseTemporary(rt);
            return small;
        }
    }
}
