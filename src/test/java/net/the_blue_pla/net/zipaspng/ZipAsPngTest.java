/*
 * ZipAsPngTest.java
 * Copyright (C) 2017, SATO_Yoshiyuki
 * This software is released under the MIT License.
 * http://opensource.org/licenses/mit-license.php
 */
package net.the_blue_pla.net.zipaspng;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.Assert;
import org.junit.Test;

@SuppressWarnings("javadoc")
public class ZipAsPngTest {

	@Test
	public void testDisguise() throws Exception {
		// 対象となるZIPファイル
		Path inputZipPath = Paths.get("./data/input/hoge.zip");
		// 元となるPNGファイル
		Path inputPngPath = Paths.get("./data/input/test.png");
		// 出力ファイル
		Path outputPath = Paths.get("./data/output/output.zip.png");
		// 想定出力データ
		Path expectedDataPath = Paths.get("./data/expected/output.zip.png");

		// 念のため、出力ファイル（前回のごみ）を削除
		Files.deleteIfExists(outputPath);

		// ZIPをPNGに偽装する
		ZipAsPng.disguise(inputZipPath, inputPngPath, outputPath);

		// あらかじめ用意していたファイルと一致チェック
		Assert.assertTrue(FileUtils.contentEquals(
				outputPath.toFile(), expectedDataPath.toFile()));

		// イメージデータとして同じかチェック
		assertSameImages(outputPath, inputPngPath);

		// ZIPファイルとして同じかチェック
		assertSameZipFiles(outputPath, inputZipPath);

		// 正常なら削除(エラー時は内容を確認できるように削除しない)
		Files.delete(outputPath);
	}

	/** 指定イメージの中身が同一かをチェック */
	private static void assertSameImages(Path pathA, Path pathB) throws IOException {
		BufferedImage imageA = ImageIO.read(pathA.toFile());
		BufferedImage imageB = ImageIO.read(pathB.toFile());

		Assert.assertEquals(imageA.getHeight(), imageB.getHeight());
		Assert.assertEquals(imageA.getWidth(), imageB.getWidth());
		Assert.assertEquals(imageA.getType(), imageB.getType());
		Assert.assertEquals(imageA.getColorModel(), imageB.getColorModel());

		for (int y = 0, maxY = imageA.getHeight(); y < maxY; y++) {
			for (int x = 0, maxX = imageA.getWidth(); x < maxX; x++) {
				Assert.assertEquals("(" + x + "," + y + ")",
						imageA.getRGB(x, y),
						imageB.getRGB(x, y));
			}
		}
	}

	/** 指定ZipFileの中身が同一かをチェック */
	private static void assertSameZipFiles(Path pathA, Path pathB) throws IOException {
		// Java標準の ZipFile では、読み込めないみたいなので
		// Commons compress の ZiPFile を使用する

		try (ZipFile zipA = new ZipFile(pathA.toFile());
				ZipFile zipB = new ZipFile(pathB.toFile())) {

			List<ZipArchiveEntry> listA = Collections.list(zipA.getEntries());
			List<ZipArchiveEntry> listB = Collections.list(zipB.getEntries());

			Assert.assertEquals(listA.size(), listB.size());

			for (int i = 0, size = listA.size(); i < size; i++) {
				ZipArchiveEntry entryA = listA.get(i);
				ZipArchiveEntry entryB = listB.get(i);
				Assert.assertEquals(entryA.getName(), entryB.getName());
				Assert.assertEquals(entryA.getSize(), entryB.getSize());
				Assert.assertEquals(entryA.getCrc(), entryB.getCrc());
				Assert.assertEquals(entryA.isDirectory(), entryB.isDirectory());
				Assert.assertEquals(entryA.isUnixSymlink(), entryB.isUnixSymlink());
				// ↑細かいチェックは手抜き

				if (entryA.isDirectory() || entryA.isUnixSymlink()) {
					continue;
				}
				// ファイルの内容をチェック
				try (InputStream inA = zipA.getInputStream(entryA);
						InputStream inB = zipB.getInputStream(entryB)) {
					if (!IOUtils.contentEquals(inA, inB)) {
						Assert.fail(entryA.getName());
					}
				}

			}
		}
	}
}
