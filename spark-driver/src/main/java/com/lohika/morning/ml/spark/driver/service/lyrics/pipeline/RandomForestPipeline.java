package com.lohika.morning.ml.spark.driver.service.lyrics.pipeline;

import static com.lohika.morning.ml.spark.distributed.library.function.map.lyrics.Column.*;
import static org.apache.spark.sql.functions.concat_ws;

import com.lohika.morning.ml.spark.driver.service.lyrics.Genre;
import com.lohika.morning.ml.spark.driver.service.lyrics.transformer.*;

import java.nio.file.Paths;
import java.util.Map;
import org.apache.spark.ml.Pipeline;
import org.apache.spark.ml.PipelineModel;
import org.apache.spark.ml.PipelineStage;
import org.apache.spark.ml.Transformer;
import org.apache.spark.ml.classification.RandomForestClassificationModel;
import org.apache.spark.ml.classification.RandomForestClassifier;
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator;
import org.apache.spark.ml.feature.StopWordsRemover;
import org.apache.spark.ml.feature.Tokenizer;
import org.apache.spark.ml.feature.Word2Vec;
import org.apache.spark.ml.feature.Word2VecModel;
import org.apache.spark.ml.param.ParamMap;
import org.apache.spark.ml.tuning.CrossValidator;
import org.apache.spark.ml.tuning.CrossValidatorModel;
import org.apache.spark.ml.tuning.ParamGridBuilder;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.types.DataTypes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component("RandomForestPipeline")
public class RandomForestPipeline extends CommonLyricsPipeline {

    @Value("${lyrics.training.set.directory.path}")
    private String lyricsTrainingSetDirectoryPath;

    @Value("${lyrics.model.directory.path}")
    private String lyricsModelDirectoryPath;

    @Value("${lyrics.training.set.filename}")
    private String fileName;

    public CrossValidatorModel classify() {
        Dataset<Row> sentences = readLyrics();

        long seed = 45678L;
        Dataset<Row>[] splits = sentences.randomSplit(new double[]{0.8, 0.2}, seed);
        Dataset<Row> trainingData = splits[0];
        Dataset<Row> testData = splits[1];

        // Remove all punctuation symbols.
        Cleanser cleanser = new Cleanser();

        // Add rowNumber based on it.
        Numerator numerator = new Numerator();

        // Split into words.
        Tokenizer tokenizer = new Tokenizer()
                .setInputCol(CLEAN.getName())
                .setOutputCol(WORDS.getName());

        // Remove stop words.
        StopWordsRemover stopWordsRemover = new StopWordsRemover()
                .setInputCol(WORDS.getName())
                .setOutputCol(FILTERED_WORDS.getName());

        // Create as many rows as words. This is needed or Stemmer.
        Exploder exploder = new Exploder();

        // Perform stemming.
        Stemmer stemmer = new Stemmer();

        Uniter uniter = new Uniter();
        Verser verser = new Verser();

        // Create model.
        Word2Vec word2Vec = new Word2Vec().setInputCol(VERSE.getName()).setOutputCol("features").setMinCount(0);

        RandomForestClassifier randomForest = new RandomForestClassifier();

        Pipeline pipeline = new Pipeline().setStages(
                new PipelineStage[]{
                        cleanser,
                        numerator,
                        tokenizer,
                        stopWordsRemover,
                        exploder,
                        stemmer,
                        uniter,
                        verser,
                        word2Vec,
                        randomForest});

        // Use a ParamGridBuilder to construct a grid of parameters to search over.
        ParamMap[] paramGrid = new ParamGridBuilder()
                .addGrid(verser.sentencesInVerse(), new int[]{16})
                .addGrid(word2Vec.vectorSize(), new int[] {300})
                .addGrid(randomForest.numTrees(), new int[] {10, 20, 30})
                .addGrid(randomForest.maxDepth(), new int[] {20, 30})
                .addGrid(randomForest.maxBins(), new int[] {32, 64, 128})
                .build();

        CrossValidator crossValidator = new CrossValidator()
                .setEstimator(pipeline)
                .setEvaluator(new MulticlassClassificationEvaluator())
                .setEstimatorParamMaps(paramGrid)
                .setNumFolds(3);

        // Run cross-validation, and choose the best set of parameters.
        CrossValidatorModel model = crossValidator.fit(trainingData);

        model.bestModel().transform(testData);

        saveModel(model, getModelDirectory());

        return model;
    }

    @Override
    Dataset<Row> readLyrics() {
        WindowSpec window = Window.partitionBy("genre").orderBy("index");
        sparkSession.udf().register("genreToLabel", (String genreName) -> Genre.valueOf(genreName.toUpperCase().replace(" ", "_")).getValue(), DataTypes.DoubleType);
        Dataset<Row> rawLyrics = sparkSession
                .read()
                .option("header", "true")
                .csv(Paths.get(lyricsTrainingSetDirectoryPath).resolve(fileName).toString());
        rawLyrics = rawLyrics.withColumn(VALUE.getName(), rawLyrics.col("lyrics"));
        rawLyrics = rawLyrics.filter(rawLyrics.col(VALUE.getName()).notEqual(""));
        rawLyrics = rawLyrics.filter(rawLyrics.col(VALUE.getName()).contains(" "));
        rawLyrics = rawLyrics.withColumn("rank", functions.row_number().over(window));

        Dataset<Row> lyrics = rawLyrics.filter("rank <= 100");

        lyrics = lyrics.drop("rank");

        lyrics = lyrics.withColumn(ID.getName(), concat_ws("-", rawLyrics.col("artist_name"), rawLyrics.col("track_name"), rawLyrics.col("release_date")));

        Dataset<Row> input = lyrics.withColumn(LABEL.getName(), functions.callUDF("genreToLabel", lyrics.col("genre")));

        // Reduce the input amount of partition minimal amount (spark.default.parallelism OR 2, whatever is less)
        input = input.coalesce(sparkSession.sparkContext().defaultMinPartitions()).cache();
        // Force caching.
        input.count();

        return input;
    }

    public Map<String, Object> getModelStatistics(CrossValidatorModel model) {
        Map<String, Object> modelStatistics = super.getModelStatistics(model);

        PipelineModel bestModel = (PipelineModel) model.bestModel();
        Transformer[] stages = bestModel.stages();

        modelStatistics.put("Sentences in verse", ((Verser) stages[7]).getSentencesInVerse());
        modelStatistics.put("Word2Vec vocabulary", ((Word2VecModel) stages[8]).getVectors().count());
        modelStatistics.put("Vector size", ((Word2VecModel) stages[8]).getVectorSize());
        modelStatistics.put("Num trees", ((RandomForestClassificationModel) stages[9]).getNumTrees());
        modelStatistics.put("Max bins", ((RandomForestClassificationModel) stages[9]).getMaxBins());
        modelStatistics.put("Max depth", ((RandomForestClassificationModel) stages[9]).getMaxDepth());

        printModelStatistics(modelStatistics);

        return modelStatistics;
    }

    @Override
    protected String getModelDirectory() {
        return getLyricsModelDirectoryPath() + "/random-forest/";
    }

}
