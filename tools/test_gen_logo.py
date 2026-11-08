import os
import tempfile
import unittest

from PIL import Image
from gen_logo import generate_gif


class LogoAnimationTest(unittest.TestCase):
    def test_animation_loops_and_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            source = os.path.join(directory, "source.png")
            output = os.path.join(directory, "animated.gif")
            Image.new("RGB", (64, 64), (25, 35, 45)).save(source)
            generate_gif(source, output)
            with Image.open(output) as gif:
                self.assertEqual(gif.size, (64, 64))
                self.assertEqual(gif.info["loop"], 0)
                self.assertGreater(gif.n_frames, 1)
                first = gif.convert("RGB").tobytes()
                changed = False
                for frame in range(1, gif.n_frames):
                    gif.seek(frame)
                    changed |= first != gif.convert("RGB").tobytes()
                self.assertTrue(changed)

    def test_missing_source_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(FileNotFoundError):
                generate_gif(os.path.join(directory, "missing.png"), os.path.join(directory, "out.gif"))


if __name__ == "__main__":
    unittest.main()
