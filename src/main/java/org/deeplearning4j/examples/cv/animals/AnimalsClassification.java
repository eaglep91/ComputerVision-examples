package org.deeplearning4j.examples.cv.animals;

import org.apache.commons.io.FilenameUtils;
import org.canova.api.io.filters.BalancedPathFilter;
import org.canova.api.io.labels.ParentPathLabelGenerator;
import org.canova.api.records.reader.RecordReader;
import org.canova.api.split.FileSplit;
import org.canova.api.split.InputSplit;
import org.canova.image.loader.BaseImageLoader;
import org.canova.image.recordreader.ImageRecordReader;
import org.canova.image.transform.*;
import org.deeplearning4j.datasets.canova.RecordReaderDataSetIterator;
import org.deeplearning4j.datasets.iterator.DataSetIterator;
import org.deeplearning4j.datasets.iterator.MultipleEpochsIterator;
import org.deeplearning4j.eval.Evaluation;
import org.deeplearning4j.nn.api.Layer;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.GradientNormalization;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.layers.*;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.deeplearning4j.util.NetSaverLoaderUtils;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Animal Classification
 *
 * Example classification of photos from 4 different animals (bear, duck, deer, turtle).
 *
 * References:
 *  - U.S. Fish and Wildlife Service (animal sample dataset): http://digitalmedia.fws.gov/cdm/
 *  - Tiny ImageNet Classification with CNN: http://cs231n.stanford.edu/reports/leonyao_final.pdf
 *
 * Note: This is losely aligned to the scala example but differences are expected in development.
 */

public class AnimalsClassification {
    protected static final Logger log = LoggerFactory.getLogger(AnimalsClassification.class);
    protected static int height = 50;
    protected static int width = 50;
    protected static int channels = 3;
    protected static int numExamples = 80;
    protected static int numLabels = 4;
    protected static int batchSize = 20;

    protected static long seed = 42;
    protected static Random rng = new Random(seed);
    protected static int listenerFreq = 1;
    protected static int iterations = 1;
    protected static int epochs = 5;
    protected static double splitTrainTest = 0.8;


    public static void main(String[] args) throws Exception {

        log.info("Load data....");
        /**
         * Data Setup -> organize and limit data file paths:
         *  - mainPath = path to image files
         *  - fileSplit = define basic dataset split with limits on format
         *  - pathFilter = define additional file load filter to limit size and balance batch content
         **/
        File mainPath = new File(System.getProperty("user.dir"), "src/main/resources/");
        FileSplit fileSplit = new FileSplit(mainPath, BaseImageLoader.ALLOWED_FORMATS, rng);
        BalancedPathFilter pathFilter = new BalancedPathFilter(rng, new ParentPathLabelGenerator(), numExamples, numLabels, batchSize);

        /**
         * Data Setup -> train test split
         *  - inputSplit = define train and test split
         **/
        InputSplit[] inputSplit = fileSplit.sample(pathFilter, numExamples*(1+splitTrainTest),  numExamples*(1-splitTrainTest));
        InputSplit trainData = inputSplit[0];
        InputSplit testData = inputSplit[1];

        /**
         * Data Setup -> transformation
         *  - *Transform = how to tranform images and generate large dataset to train on
         **/
//        ImageTransform flipTransform = new MultiImageTransform(rng,
//                new FlipImageTransform(0),
//                new ShowImageTransform("Flipped Image", 1));
        ImageTransform flipTransform = new FlipImageTransform(rng);
        ImageTransform warpTransform = new WarpImageTransform(rng, 42);
        List<ImageTransform> transforms = Arrays.asList(new ImageTransform[] {null, flipTransform, warpTransform});

        log.info("Build model....");
        // Tiny model configuration
        MultiLayerConfiguration confTiny = new NeuralNetConfiguration.Builder()
                .seed(seed)
                .iterations(iterations)
                .activation("relu")
                .weightInit(WeightInit.XAVIER)
                .gradientNormalization(GradientNormalization.RenormalizeL2PerLayer)
                .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                .updater(Updater.NESTEROVS)
                .learningRate(0.01)
                .momentum(0.9)
                .regularization(true)
                .l2(0.04)
                .useDropConnect(true)
                .list()
                .layer(0, new ConvolutionLayer.Builder(5, 5)
                        .name("cnn1")
                        .nIn(channels)
                        .stride(1, 1)
                        .padding(2, 2)
                        .nOut(32)
                        .build())
                .layer(1, new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX)
                        .kernelSize(3, 3)
                        .name("pool1")
                        .build())
                .layer(2, new LocalResponseNormalization.Builder(3, 5e-05, 0.75).build())
                .layer(3, new ConvolutionLayer.Builder(5, 5)
                        .name("cnn2")
                        .stride(1, 1)
                        .padding(2, 2)
                        .nOut(32)
                        .build())
                .layer(4, new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX)
                        .kernelSize(3, 3)
                        .name("pool2")
                        .build())
                .layer(5, new LocalResponseNormalization.Builder(3, 5e-05, 0.75).build())
                .layer(6, new ConvolutionLayer.Builder(5, 5)
                        .name("cnn3")
                        .stride(1, 1)
                        .padding(2, 2)
                        .nOut(64)
                        .build())
                .layer(7, new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX)
                        .kernelSize(3, 3)
                        .name("pool3")
                        .build())
                .layer(8, new DenseLayer.Builder()
                        .name("ffn1")
                        .nOut(250)
                        .dropOut(0.5)
                        .build())
                .layer(9, new OutputLayer.Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
                        .nOut(numLabels)
                        .activation("softmax")
                        .build())
                .backprop(true).pretrain(false)
                .cnnInputSize(height, width, channels).build();

        MultiLayerNetwork network = new MultiLayerNetwork(confTiny);
        network.init();
        network.setListeners(new ScoreIterationListener(listenerFreq));

        /**
         * Data Setup -> define how to load data into net:
         *  - recordReader = the reader that loads and converts image data pass in inputSplit to initialize
         *  - dataIter = a generator that only loads one batch at a time into memory to save memory
         *  - trainIter = uses MultipleEpochsIterator to ensure model runs through the data for all epochs
         **/
        ImageRecordReader recordReader = new ImageRecordReader(width, height, channels, new ParentPathLabelGenerator());
        DataSetIterator dataIter;
        MultipleEpochsIterator trainIter;

        // Train with transformations
        log.info("Train model....");
        for(ImageTransform transform: transforms) {
            recordReader.initialize(trainData, transform);
            dataIter = new RecordReaderDataSetIterator(recordReader, batchSize, 1, numLabels);
            trainIter = new MultipleEpochsIterator(epochs, dataIter);
            network.fit(trainIter);
        }

        log.info("Evaluate model....");
        recordReader.initialize(testData);
        dataIter = new RecordReaderDataSetIterator(recordReader, 20, 1, numLabels);
        Evaluation eval = network.evaluate(dataIter);
        log.info(eval.stats(true));

        // Example on how to get predict results with trained model
        dataIter.reset();
        DataSet testDataSet = dataIter.next();
        String expectedResult = testDataSet.getLabelName(0);
        List<String> predict = network.predict(testDataSet);
        String modelResult = predict.get(0);
        System.out.print("\nFor a single example that is labeled " + expectedResult+ " the model predicted " + modelResult + "\n\n");

        log.info("Save model....");
        String basePath = FilenameUtils.concat(System.getProperty("user.dir"), "src/main/resources/");
        NetSaverLoaderUtils.saveNetworkAndParameters(network, basePath);
        NetSaverLoaderUtils.saveUpdators(network, basePath);

        log.info("****************Example finished********************");

    }

}
