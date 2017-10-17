/*
 * ZipAsPng.java
 * Copyright (C) 2017, SATO_Yoshiyuki
 * This software is released under the MIT License.
 * http://opensource.org/licenses/mit-license.php
 */
package net.the_blue_pla.net.zipaspng;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;

/** ZIPファイルをPNGファイルに偽装する */
public final class ZipAsPng {

	/** 見つからない場合の定数 */
	private static final int NOT_FOUND = -1;
	/** CEN のシグネチャ PK0102 */
	private static final int SIG_CEN = 0x02014b50;
	/** EOCD のシグネチャ PK0506 */
	private static final int SIG_EOCD = 0x06054b50;
	/** PNGヘッダのサイズ */
	private static final int SIZE_PNG_HEAD = 8;
	/** PNGのIHDRチャンクのサイズ */
	private static final int SIZE_PNG_IHDR = 4 + 4 + 0x0d + 4;
	/** ZIPのEOCDのサイズ */
	private static final int SIZE_ZIP_EOCD = 22;
	/** ZIPのCENのサイズ */
	private static final int SIZE_ZIP_CEN = 46;
	/**
	 * ZIPのオフセット補正幅.<br>
	 * PNGヘッダー + IHDRチャンク+ ZIPコンテナチャンクの長さ(4)+チャンクタイプ(4)
	 */
	private static final int OFFSET_ZIP = SIZE_PNG_HEAD + SIZE_PNG_IHDR + 4 + 4;
	/** PNGフォーマットのヘッダ部＋IHDRチャンクの先頭部分 . ミュータブルなので注意 */
	private static final byte[] HEAD_PNG = {
			// PNGヘッダー
			(byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G',
			0x0d, 0x0a, 0x1a, 0x0a,
			// IHDRチャンク
			0x00, 0x00, 0x00, 0x0d,
			(byte) 'I', (byte) 'H', (byte) 'D', (byte) 'R',
	};

	/** インスタンス化抑止 */
	private ZipAsPng() {
		// NOTREACHED
	}

	/**
	 * ZIPをPNGに偽装する.
	 *
	 * @param targetZip
	 *            偽装対象のZIPファイル
	 * @param basePng
	 *            元となるPNGファイル
	 * @param outZipAsPng
	 *            出力ファイル
	 * @throws IOException
	 *             入出力エラー時
	 * @throws IllegalArgumentException
	 *             ZIP/PNGファイルがおかしい場合
	 * @throws NullPointerException
	 *             引数がnullの場合
	 */
	public static void disguise(Path targetZip,
			Path basePng, Path outZipAsPng) throws IOException {

		long zipSize = Files.size(targetZip);
		if (Integer.MAX_VALUE < zipSize) {
			// ZIPサイズが、PNGのチャンクの最大値を超えている
			throw new IllegalArgumentException("ZIPのサイズが大きすぎる. path=" + targetZip);
		}
		long pngSize = Files.size(basePng);
		if (Integer.MAX_VALUE < pngSize) {
			// PNGサイズが、大きすぎる
			throw new IllegalArgumentException("PNGのサイズが大きすぎる. path=" + basePng);
		}

		try (FileChannel inZip = FileChannel.open(targetZip, StandardOpenOption.READ);
				FileChannel inPng = FileChannel.open(basePng, StandardOpenOption.READ);
				FileChannel out = FileChannel.open(outZipAsPng, StandardOpenOption.WRITE,
						StandardOpenOption.CREATE_NEW)) {

			ByteBuffer zipContent = inZip.map(MapMode.READ_ONLY, 0, zipSize)
					.order(ByteOrder.LITTLE_ENDIAN);
			ByteBuffer pngContent = inPng.map(MapMode.READ_ONLY, 0, pngSize)
					.order(ByteOrder.BIG_ENDIAN);

			// PNGファイルのヘッダ+IHDR前半チェック
			ByteBuffer pngHeader = subBuffer(pngContent, HEAD_PNG.length);
			ByteBuffer pngMagicNumber = ByteBuffer.wrap(HEAD_PNG);
			if (pngMagicNumber.compareTo(pngHeader) != 0) {
				throw new IllegalArgumentException(
						"PNGヘッダがおかしい. path=" + basePng.toString());
			}
			if (lastIndexOfSigneture(pngContent) != NOT_FOUND) {
				throw new IllegalArgumentException(
						"PNGにEOCDシグネチャが含まれている. path=" + basePng.toString());
			}

			// ZIPファイルのEOCDの位置を取得
			int positionOfEOCD = lastIndexOfSigneture(zipContent);
			if (positionOfEOCD == NOT_FOUND) {
				// EOCDのシグネチャ が見つからない
				throw new IllegalArgumentException("ZIPファイルでEOCDが見つからない. path=" + targetZip);
			}

			// ZIPファイルのCENの位置を取得
			int positionOfCEN = zipContent.getInt(positionOfEOCD + ZipFile.ENDOFF);
			if (positionOfEOCD <= positionOfCEN) {
				throw new IllegalArgumentException("CENとEOCDの順番が不正. "
						+ "path=" + targetZip
						+ ", cen=0x" + Integer.toHexString(positionOfCEN)
						+ ", eocd=0x" + Integer.toHexString(positionOfEOCD));
			}
			if (SIG_CEN != zipContent.getInt(positionOfCEN)) {
				// 指定された位置にCENがない
				throw new IllegalArgumentException("ZIPファイルでCENが見つからない. path=" + targetZip);
			}

			// CENの合計サイズを取得
			int sizeOfCen = zipContent.getInt(positionOfEOCD + ZipFile.ENDSIZ);

			pngContent.clear();
			zipContent.clear();
			CRC32 crc = new CRC32();

			// PNGのヘッダを出力（コピー）
			write(out, subBuffer(pngContent, SIZE_PNG_HEAD));
			// IHDRチャンクを出力（コピー）
			write(out, subBuffer(pngContent, SIZE_PNG_IHDR));

			// ZIPコンテナチャンクの「長さ」を出力
			writeBigEndian(out, (int) zipSize);

			// ZIPコンテナチャンクの「チャンクタイプ」を出力
			ByteBuffer type = ByteBuffer.wrap("ziPc".getBytes(StandardCharsets.US_ASCII));
			crc.update(type.duplicate());
			write(out, type);

			// ZIPファイルのCENの手前までをコピー
			ByteBuffer sub = subBuffer(zipContent, positionOfCEN);
			crc.update(sub.duplicate());
			write(out, sub);

			// CENを繰り返し処理する
			for (int size = 0; size < sizeOfCen;) {

				// CENを読み込み、LOCのoffsetを補正して出力
				ByteBuffer cen = copyOf(subBuffer(zipContent, SIZE_ZIP_CEN));
				int offsetOfLocalHeader = cen.getInt(ZipFile.CENOFF);
				cen.putInt(ZipFile.CENOFF, offsetOfLocalHeader + OFFSET_ZIP);
				crc.update(cen.duplicate());
				write(out, cen);
				size += SIZE_ZIP_CEN;

				// CENの後ろのデータ(ファイル名、追加情報、コメント)を出力
				int extraContentsLength = cen.getInt(ZipFile.CENNAM)
						+ cen.getInt(ZipFile.CENEXT)
						+ cen.getInt(ZipFile.CENCOM);
				ByteBuffer extraContents = subBuffer(zipContent, extraContentsLength);
				crc.update(extraContents.duplicate());
				write(out, extraContents);
				size += extraContentsLength;
			}
			if (zipContent.position() != positionOfCEN + sizeOfCen) {
				// CENの合計サイズがおかしい
				throw new IllegalArgumentException("CENのサイズがあっていない");
			}

			if (positionOfCEN + sizeOfCen < positionOfEOCD) {
				// CENの終わりとEOCDの間に何かあればそれを出力
				ByteBuffer gap = subBuffer(zipContent, positionOfEOCD - (positionOfCEN + sizeOfCen));
				crc.update(gap.duplicate());
				write(out, gap);
			}

			// EOCDを読み込み、CENのoffsetを補正して出力
			ByteBuffer eocd = copyOf(subBuffer(zipContent, SIZE_ZIP_EOCD));
			eocd.putInt(ZipFile.ENDOFF, positionOfCEN + OFFSET_ZIP);
			crc.update(eocd.duplicate());
			write(out, eocd);

			// EOCDの後ろに何かあればそれを出力
			crc.update(zipContent.duplicate());
			write(out, zipContent);

			// ZIPコンテナチャンクの「CRC」を出力
			int valueOfCrc32 = (int) crc.getValue();
			if (valueOfCrc32 == Integer.reverseBytes(SIG_EOCD)) {
				// 不幸にもCRC32の結果が EOCDのシグネチャになった場合
				throw new IllegalArgumentException(
						"ZIPコンテナチャンクのCRC32が偶然、EOCDのシグネチャと同じ値になってしまった");
				// TODO 何か対策を考える？
				// 案1：チャンクタイプを２種類用意して、切り替える
				// 案2:チャンクデータに余計なデータを出力して、CRC32の値を調整する
			}
			writeBigEndian(out, valueOfCrc32);

			// PNGの残り(IHDRの後ろ)のデータをコピー
			write(out, pngContent);
		}
	}

	/**
	 * サブバッファの切り出し.<br>
	 * 指定されたバッファの現在位置から、指定長分のサブバッファを返す.<br>
	 * 元のバッファの新しい位置は、指定長分進む.<br>
	 * サブバッファの位置は0、容量とリミットは指定長になる.<br>
	 * また、バイトオーダーは、元のバッファと同じになる.<br>
	 *
	 * @param buff
	 *            バッファ
	 * @param length
	 *            長さ
	 * @return サブバッファ
	 * @throws NullPointerException
	 *             バッファがnullの場合
	 * @throws IllegalArgumentException
	 *             バッファに長さ分の容量がない場合
	 */
	private static ByteBuffer subBuffer(ByteBuffer buff, int length) {
		int newPosition = buff.position() + length;
		ByteBuffer sub = buff.duplicate();
		sub.limit(newPosition);
		buff.position(newPosition); // 切り出した分、元のバッファを進める
		return sub.slice().order(buff.order());
	}

	/**
	 * バッファのコピー.<br>
	 * 編集可能な新しいバッファを生成し、 コピー元バッファの現在位置からリミットまでをそのバッファにコピーする.<br>
	 * コピー元バッファの新しい位置は、リミットと同じになる.<br>
	 * 生成されたバッファの位置は0で、リミットは容量と同じになる.<br>
	 * また、バイトオーダーは、コピー元バッファと同じになる.<br>
	 *
	 * @param buff
	 *            コピー元バッファ
	 * @return コピーしたバッファ
	 */
	private static ByteBuffer copyOf(ByteBuffer buff) {
		ByteBuffer newBuffer = ByteBuffer.allocate(buff.remaining())
				.order(buff.order()).put(buff);
		newBuffer.clear();
		return newBuffer;
	}

	/** 出力チャネルにバッファを出力 */
	private static void write(FileChannel out, ByteBuffer buff)
			throws IOException {
		while (buff.hasRemaining()) {
			int length = out.write(buff);
			if (length == 0) {
				throw new IOException("チャネルに書き込めない");
			}
		}
	}

	/** 出力チャネルに4byte整数をBIG_ENDIANで出力 */
	private static void writeBigEndian(FileChannel out, int value)
			throws IOException {
		ByteBuffer buff = ByteBuffer.allocate(Integer.BYTES)
				.order(ByteOrder.BIG_ENDIAN).putInt(value);
		buff.clear();
		write(out, buff);
	}

	/** シグネチャを後ろから検索する */
	private static int lastIndexOfSigneture(ByteBuffer buff) {
		// TODO 検索アルゴリズムのBM法やKMP法を使うべき？
		buff = buff.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
		for (int index = buff.limit() - Integer.BYTES; 0 <= index; index--) {
			int value = buff.getInt(index);
			if (value == SIG_EOCD) {
				return index;
			}
		}
		return NOT_FOUND;
		// TODO Optional<Integer> にしたほうがよいか？
	}
}
