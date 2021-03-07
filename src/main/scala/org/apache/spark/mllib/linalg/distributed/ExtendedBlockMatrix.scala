package org.apache.spark.mllib.linalg.distributed

import breeze.linalg.{*, CSCMatrix, pinv, DenseMatrix => BDM, DenseVector => BDV, Matrix => BM}
import org.apache.spark.mllib.linalg._
import org.apache.spark.rdd.{PairRDDFunctions, RDD}
import org.apache.spark.sql.SparkSession

import scala.collection.mutable.ArrayBuffer

class ExtendedBlockMatrix(blocks: RDD[((Int, Int), Matrix)],
						  rowsPerBlock: Int,
						  colsPerBlock: Int,
						  nRows: Long,
						  nCols: Long) extends BlockMatrix(blocks, rowsPerBlock, colsPerBlock, nRows, nCols) {
	/**
	 * Performs the Hadamard operation between this [[BlockMatrix]] to `other`, another [[BlockMatrix]].
	 * The `rowsPerBlock` of this matrix must equal the `rowsPerBlock` of `other`,
	 * and the `colsPerBlock` of this matrix must equal the `colsPerBlock` of `other`.
	 */
	def hadamard(other: ExtendedBlockMatrix,
				 op: (BM[Double], BM[Double]) => BM[Double] = (m1, m2) => m1 *:* m2): ExtendedBlockMatrix = {
		this.blockMap(other, op)
	}
	
	/**
	 * Applies the `op` globally on this [[ExtendedBlockMatrix]].
	 */
	def applyOperation(op: BDM[Double] => BDM[Double]): ExtendedBlockMatrix = {
		new ExtendedBlockMatrix(
			blocks.map(b => {
				b._2 match {
					case _: DenseMatrix => {
						(b._1, Matrices.fromBreeze(op(b._2.asBreeze.asInstanceOf[BDM[Double]])))
					}
					case _: SparseMatrix => {
						(b._1, Matrices.fromBreeze(op(b._2.asBreeze.asInstanceOf[CSCMatrix[Double]].toDense)))
					}
				}
				
			}).reduceByKey(createPartitioner(), (a, b) => Matrices.fromBreeze(a.asBreeze + b.asBreeze)),
			rowsPerBlock,
			colsPerBlock,
			nRows,
			nCols
		)
	}
	
	/**
	 * Multiplies this [[ExtendedBlockMatrix]] by an Array.
	 */
	def multiplyByArray(array: Array[Double])(implicit spark: SparkSession): ExtendedBlockMatrix = {
		val nbColBlocks = (array.length.toDouble / colsPerBlock.toDouble).ceil.toInt
		val vectorMap = (for (i <- 0 until nbColBlocks) yield i -> new BDV[Double](array.slice(i * colsPerBlock, (i + 1) * colsPerBlock)).t).toMap
		spark.sparkContext.broadcast(vectorMap)
		new ExtendedBlockMatrix(
			blocks.map{ case ((i, j), matrix) => {
				val bMatrix = matrix.asBreeze.toDenseMatrix
				for (r <- 0 until bMatrix.rows) {
					bMatrix(r, ::) := bMatrix(r, ::) *:* vectorMap(j)
				}
				((i, j), Matrices.fromBreeze(bMatrix))
			}
			}.reduceByKey(createPartitioner(), (a, b) => Matrices.fromBreeze(a.asBreeze + b.asBreeze)),
			rowsPerBlock,
			colsPerBlock,
			nRows,
			nCols
		)
	}
	
	/**
	 * Converts this [[ExtendedBlockMatrix]] to a sparse Breeze matrix.
	 */
	def toSparseBreeze(): CSCMatrix[Double] = {
		val m = CSCMatrix.zeros[Double](numRows().toInt, numCols().toInt)
		for (r <- this.toCoordinateMatrix().entries.collect()) {
			val row = r.i.toInt
			val col = r.j.toInt
			val value = r.value
			m(row, col) = value
		}
		m
	}
	
	/**
	 * Computes the l1 norm of each column of this [[ExtendedBlockMatrix]].
	 */
	def normL1(): Array[Double] = {
		this.blocks.aggregate((for (i <- 0L until nCols) yield 0.0).toArray)((norm, _m1) => {
			val ((_, _), m1) = _m1
			m1.foreachActive((i, j, v) => norm(j) += math.abs(v))
			norm
		}, (u1, u2) => (for (i <- u1.indices) yield u1(i) + u2(i)).toArray)
	}
	
	/**
	 * Computes the l2 norm of each column of this [[ExtendedBlockMatrix]].
	 */
	def normL2(): Array[Double] = {
		val norms = this.blocks.aggregate((for (i <- 0L until nCols) yield 0.0).toArray)((norm, _m1) => {
			val ((_, _), m1) = _m1
			m1.foreachActive((i, j, v) => norm(j) += v * v)
			norm
		}, (u1, u2) => (for (i <- u1.indices) yield u1(i) + u2(i)).toArray)
		for (norm <- norms) yield math.sqrt(norm)
	}
	
	/**
	 * Computes the forbenius norm of this [[ExtendedBlockMatrix]], by adding the absolute value of all the
	 * values of the matrix.
	 */
	def frobeniusNorm(): Double = {
		math.sqrt(this.toCoordinateMatrix().entries.aggregate(0.0)((p, me) => p + math.pow(me.value, 2), (p1, p2) => p1 + p2))
	}
	
	/**
	 * Applies the `pinv` function from Breeze.
	 */
	def pinverse()(implicit spark: SparkSession): ExtendedBlockMatrix = {
		ExtendedBlockMatrix.fromBreeze(pinv(this.toSparseBreeze()))
	}
	
	/**
	 * Computes the inverse of the matrix with the SVD of Spark.
	 * From https://stackoverflow.com/questions/29869567/spark-distributed-matrix-multiply-and-pseudo-inverse-calculating
	 *
	 * @return
	 */
	def sparkPinverse()(implicit spark: SparkSession): ExtendedBlockMatrix = {
		val X = this.toIndexedRowMatrix().toRowMatrix()
		val nCoef = X.numCols.toInt
		val svd = X.computeSVD(nCoef, computeU = true)
		if (svd.s.size < nCoef) {
			sys.error(s"RowMatrix.computeInverse called on singular matrix.")
		}
		
		// Create the inv diagonal matrix from S
		val invS = DenseMatrix.diag(new DenseVector(svd.s.toArray.map(x => math.pow(x,-1))))
		
		// U cannot be a RowMatrix
		val U = new DenseMatrix(svd.U.numRows().toInt,svd.U.numCols().toInt,svd.U.rows.collect.flatMap(x => x.toArray))
		
		// If you could make V distributed, then this may be better. However its alreadly local...so maybe this is fine.
		val V = svd.V
		// inv(X) = V*inv(S)*transpose(U)  --- the U is already transposed.
		ExtendedBlockMatrix.fromBreeze(((V.multiply(invS)).multiply(U)).asBreeze)
	}
	
	/**
	 * Converts to a [[CoordinateMatrix]] and keeps the 0 entries.
	 *
	 */
	def toCoordinateMatrixWithZeros(): CoordinateMatrix = {
		val entryRDD = blocks.flatMap { case ((blockRowIndex, blockColIndex), mat) =>
			val rowStart = blockRowIndex.toLong * rowsPerBlock
			val colStart = blockColIndex.toLong * colsPerBlock
			val entryValues = new ArrayBuffer[MatrixEntry]()
			mat.foreachActive { (i, j, v) =>
				if (i < numRows() - rowStart) entryValues += new MatrixEntry(rowStart + i, colStart + j, v)
			}
			entryValues
		}
		new CoordinateMatrix(entryRDD, numRows(), numCols())
	}
}

object ExtendedBlockMatrix {
	/**
	 * Converts a [[BlockMatrix]] to a [[ExtendedBlockMatrix]].
	 */
	implicit def fromBlockMatrix(matrix: BlockMatrix): ExtendedBlockMatrix = {
		new ExtendedBlockMatrix(matrix.blocks,
			matrix.rowsPerBlock,
			matrix.colsPerBlock,
			matrix.numRows(),
			matrix.numCols())
	}
	
	/**
	 * Converts a Breeze matrix to a [[ExtendedBlockMatrix]].
	 */
	def fromBreeze(matrix: BM[Double])(implicit spark: SparkSession): ExtendedBlockMatrix = {
		var data = Seq[MatrixEntry]()
		matrix.foreachPair((key, value) => {
			data +:= MatrixEntry(key._1.toLong, key._2.toLong, value)
		})
		
		new CoordinateMatrix(spark.sparkContext.parallelize(data), matrix.rows.toLong, matrix.cols.toLong).toBlockMatrix()
	}
	
	/**
	 * Creates an [[ExtendedBlockMatrix]] with random values.
	 */
	def random(nbRows: Long, nbCols: Long)(implicit spark: SparkSession): ExtendedBlockMatrix = {
		val randomMatrixEntries = spark.sparkContext.parallelize(
			for (i <- 0L until nbRows; j <- 0L until nbCols) yield
				MatrixEntry(i, j, scala.util.Random.nextDouble())
		)
		
		new CoordinateMatrix(randomMatrixEntries, nbRows, nbCols).toBlockMatrix()
	}
	
	/**
	 * Creates an [[ExtendedBlockMatrix]] with gaussian values.
	 */
	def gaussian(nbRows: Long, nbCols: Long)(implicit spark: SparkSession): ExtendedBlockMatrix = {
		val randomMatrixEntries = spark.sparkContext.parallelize(
			for (i <- 0L until nbRows; j <- 0L until nbCols) yield
				MatrixEntry(i, j, scala.util.Random.nextGaussian())
		)
		
		new CoordinateMatrix(randomMatrixEntries, nbRows, nbCols).toBlockMatrix()
	}
	
	/**
	 * Performs the "MTTKRP" (Matricized Tensor Times Khatri Rao Product) between the tensor as
	 * a [[CoordinateMatrix]], and a list of [[BlockMatrix]].
	 *
	 */
	def mttkrp(tensor: PairRDDFunctions[Int, MatrixEntry], dimensions: Array[BlockMatrix], dimensionsSize: Array[Long],
			   currentDimensionSize: Long, rank: Int,
			   rowsPerBlock: Int = 1024, colsPerBlock: Int = 1024): BlockMatrix = {
		require(rowsPerBlock > 0,
			s"rowsPerBlock needs to be greater than 0. rowsPerBlock: $rowsPerBlock")
		require(colsPerBlock > 0,
			s"colsPerBlock needs to be greater than 0. colsPerBlock: $colsPerBlock")
		
		val othersBlocks = for (matrix <- dimensions) yield matrix.blocks.map {case ((blockRowIndex, blockColIndex), block) => blockRowIndex -> block.asBreeze}.collectAsMap()
		
		val blocks: RDD[((Int, Int), Matrix)] = tensor.aggregateByKey(BDM.zeros[Double](math.min(rowsPerBlock, currentDimensionSize.toInt), rank))((m, entry) => {
			// Row id in the resulting block
			val rowId = entry.i % rowsPerBlock
			
			// Find the corresponding index in each matrices of the khatri rao product
			var currentJ = entry.j
			val dimensionsIndex = for (i <- dimensionsSize.indices) yield {
				val dimensionJ = (currentJ % dimensionsSize(i)).toInt
				currentJ -= dimensionJ
				currentJ /= dimensionsSize(i)
				dimensionJ
			}
			
			// Find the corresponding value in the corresponding block of the matrices of the khatri rao product
			for (r <- 0 until rank) {
				var value = entry.value
				for (i <- dimensionsIndex.indices) {
					val currentIndex = dimensionsIndex(i)
					val currentMatrix = othersBlocks(i)(if (currentIndex == 0) 0 else math.ceil(currentIndex / rowsPerBlock).toInt)
					value *= currentMatrix(currentIndex % rowsPerBlock, r)
				}
				
				m(rowId.toInt, r) += value
			}
			m
		}, _ +:+ _)
			.map { case (blockRowIndex, matrix) => ((blockRowIndex, 0), Matrices.fromBreeze(matrix)) }
		new BlockMatrix(blocks, rowsPerBlock, colsPerBlock, currentDimensionSize, rank)
	}
	
	/**
	 * Performs the "MTTKRP" (Matricized Tensor Times Khatri Rao Product) between the tensor as
	 * a [[CoordinateMatrix]], and a list of [[BlockMatrix]]. To use when the rank > colsPerBlock
	 *
	 */
	def mttkrpHighRank(tensor: PairRDDFunctions[Int, MatrixEntry], dimensions: Array[BlockMatrix], dimensionsSize: Array[Long],
					   currentDimensionSize: Long, rank: Int,
					   rowsPerBlock: Int = 1024, colsPerBlock: Int = 1024)(implicit spark: SparkSession): BlockMatrix = {
		require(rowsPerBlock > 0,
			s"rowsPerBlock needs to be greater than 0. rowsPerBlock: $rowsPerBlock")
		require(colsPerBlock > 0,
			s"colsPerBlock needs to be greater than 0. colsPerBlock: $colsPerBlock")
		
		val nbColBlocks = (rank.toDouble / colsPerBlock.toDouble).ceil.toInt
		
		val othersBlocks = for (matrix <- dimensions) yield matrix.blocks.map {case ((blockRowIndex, blockColIndex), block) => (blockRowIndex, blockColIndex) -> block.asBreeze.toDenseMatrix}.collectAsMap()
		spark.sparkContext.broadcast(othersBlocks)
		val blocks: RDD[((Int, Int), Matrix)] = tensor.aggregateByKey({
			(for (_ <- 0 until nbColBlocks - 1) yield
				BDM.zeros[Double](math.min(rowsPerBlock, currentDimensionSize.toInt), colsPerBlock)
				) :+ BDM.zeros[Double](math.min(rowsPerBlock, currentDimensionSize.toInt), rank % colsPerBlock)
		}
		)((m, entry) => {
			// Row id in the resulting block
			val rowId = entry.i % rowsPerBlock
			
			// Find the corresponding index in each matrices of the khatri rao product
			var currentJ = entry.j
			val dimensionsIndex = for (i <- dimensionsSize.indices) yield {
				val dimensionJ = (currentJ % dimensionsSize(i)).toInt
				currentJ -= dimensionJ
				currentJ /= dimensionsSize(i)
				dimensionJ
			}
			
			// Find the corresponding value in the corresponding block of the matrices of the khatri rao product
			for (c <- 0 until nbColBlocks) {
				var value = BDV.fill[Double](if (c < nbColBlocks - 1) colsPerBlock else rank % colsPerBlock, entry.value).t
				for (i <- dimensionsIndex.indices) {
					val currentIndex = dimensionsIndex(i)
					val currentMatrix = othersBlocks(i)(if (currentIndex == 0) (0, c) else (math.ceil(currentIndex / rowsPerBlock).toInt, c))
					value := value *:* currentMatrix(currentIndex % rowsPerBlock, ::)
				}
				m(c)(rowId.toInt, ::) := m(c)(rowId.toInt, ::) +:+ value
			}
			m
		}, (m1, m2) => for (c <- 0 until nbColBlocks) yield m1(c) +:+ m2(c))
			.flatMap { case (blockRowIndex, matrices) => for (c <- 0 until nbColBlocks) yield ((blockRowIndex, c), Matrices.fromBreeze(matrices(c))) }
		
		new BlockMatrix(blocks, rowsPerBlock, colsPerBlock, currentDimensionSize, rank)
	}
	
	/**
	 * Compute the Factor Match Score for 2 sets of matrices.
	 *
	 * @param currentMatrices
	 * @param currentLambdas
	 * @param lastIterationMatrices
	 * @param lastIterationLambdas
	 * @return
	 */
	def factorMatchScore(currentMatrices: Array[ExtendedBlockMatrix], currentLambdas: Array[Double],
						 lastIterationMatrices: Array[ExtendedBlockMatrix], lastIterationLambdas: Array[Double]): Double = {
		val matricesMultiplied = for (i <- 0 until currentMatrices.length) yield currentMatrices(i).transpose.multiply(lastIterationMatrices(i)).toSparseBreeze()
		val currentMatricesNorms = for (i <- 0 until currentMatrices.length) yield currentMatrices(i).normL2()
		val lastIterationMatricesNorms = for (i <- 0 until currentMatrices.length) yield lastIterationMatrices(i).normL2()
		var score = 0.0
		for (rank <- 0 until currentLambdas.length) {
			val e1 = currentLambdas(rank) * currentMatricesNorms.aggregate(1.0)((v, l) => v * l(rank), (v1, v2) => v1 * v2)
			val e2 = lastIterationLambdas(rank) * lastIterationMatricesNorms.aggregate(1.0)((v, l) => v * l(rank), (v1, v2) => v1 * v2)
			val penalty = 1 - (math.abs(e1 - e2) / math.max(e1, e2))
			var tmpScore = 1.0
			for (i <- 0 until currentMatrices.length) {
				tmpScore *= math.abs(matricesMultiplied(i)(rank, rank)) / (currentMatricesNorms(i)(rank) * lastIterationMatricesNorms(i)(rank))
			}
			score += penalty * tmpScore
		}
		score / currentLambdas.length
	}
}
