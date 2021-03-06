/*******************************************************************************
 * Copyright (c) 2014 CWI
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *
 *   * Michael Steindorfer - Michael.Steindorfer@cwi.nl - CWI
 *******************************************************************************/
package nl.cwi.swat.jmh_dscg_benchmarks;

import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import nl.cwi.swat.jmh_dscg_benchmarks.BenchmarkUtils.DataType;
import nl.cwi.swat.jmh_dscg_benchmarks.BenchmarkUtils.SampleDataSelection;
import nl.cwi.swat.jmh_dscg_benchmarks.BenchmarkUtils.ValueFactoryFactory;

import org.eclipse.imp.pdb.facts.IMap;
import org.eclipse.imp.pdb.facts.IMapWriter;
import org.eclipse.imp.pdb.facts.IValue;
import org.eclipse.imp.pdb.facts.IValueFactory;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class JmhMapBenchmarks {

	@Param({ "MAP" })
	public DataType dataType;

	@Param({ "MATCH" })
	public SampleDataSelection sampleDataSelection;

	@Param
	public ValueFactoryFactory valueFactoryFactory;

	/*
	 * (for (i <- 0 to 23) yield
	 * s"'${Math.pow(2, i).toInt}'").mkString(", ").replace("'", "\"")
	 */
	@Param({ "1", "2", "4", "8", "16", "32", "64", "128", "256", "512", "1024", "2048", "4096",
			"8192", "16384", "32768", "65536", "131072", "262144", "524288", "1048576", "2097152",
			"4194304", "8388608" })
	protected int size;

	@Param({ "0" }) // "1", "2", "3", "4", "5", "6", "7", "8", "9"
	protected int run;
	
	@Param
	public ElementProducer producer;

	public IValueFactory valueFactory;

	public IMap testMap;
	private IMap testMapRealDuplicate;
	private IMap testMapDeltaDuplicate;

	private IMap testMapRealDuplicateSameSizeButDifferent;
	
	public IValue VALUE_EXISTING;
	public IValue VALUE_NOT_EXISTING;

	public static final int CACHED_NUMBERS_SIZE = 8;
	public IValue[] cachedNumbers = new IValue[CACHED_NUMBERS_SIZE];
	public IValue[] cachedNumbersNotContained = new IValue[CACHED_NUMBERS_SIZE];

	private IMap singletonMapWithExistingValue;
	private IMap singletonMapWithNotExistingValue;

	@Setup(Level.Trial)
	public void setUp() throws Exception {		
		setUpTestMapWithRandomContent(size, run);

		switch (sampleDataSelection) {

		/*
		 * random integers might or might not be in the dataset
		 */
		case RANDOM: {
			// random data generator with fixed seed
			/* seed == Mersenne Prime #8 */
			Random randForOperations = new Random(2147483647L);

			for (int i = 0; i < CACHED_NUMBERS_SIZE; i++) {
				cachedNumbers[i] = producer.createFromInt(randForOperations.nextInt());
			}
		}

		/*
		 * random integers are in the dataset
		 */
		case MATCH: {
			// random data generator with fixed seed
			int seedForThisTrial = BenchmarkUtils.seedFromSizeAndRun(size, run);
			Random rand = new Random(seedForThisTrial);

			for (int i = 0; i < CACHED_NUMBERS_SIZE; i++) {
				if (i >= size) {
					cachedNumbers[i] = cachedNumbers[i % size];
				} else {
					cachedNumbers[i] = producer.createFromInt(rand.nextInt());
				}
			}

			// random data generator with fixed seed
			/* seed == Mersenne Prime #8 */
			Random anotherRand = new Random(2147483647L);

			for (int i = 0; i < CACHED_NUMBERS_SIZE; i++) {
				/*
				 * generate random values until a value not part of the data
				 * strucure is found
				 */
				boolean found = false;
				while (!found) {
					final IValue candidate = producer.createFromInt(anotherRand.nextInt());

					if (testMap.containsKey(candidate)) {
						continue;
					} else {
						cachedNumbersNotContained[i] = candidate;
						found = true;
					}
				}
			}

			// assert (contained)
			for (IValue sample : cachedNumbers) {
				if (!testMap.containsKey(sample)) {
					throw new IllegalStateException();
				}
			}

			// assert (not contained)
			for (IValue sample : cachedNumbersNotContained) {
				if (testMap.containsKey(sample)) {
					throw new IllegalStateException();
				}
			}
		}
		}

		final IMapWriter mapWriter1 = valueFactory.mapWriter();
		mapWriter1.put(VALUE_EXISTING, VALUE_EXISTING);
		singletonMapWithExistingValue = mapWriter1.done();

		final IMapWriter mapWriter2 = valueFactory.mapWriter();
		mapWriter2.put(VALUE_NOT_EXISTING, VALUE_NOT_EXISTING);
		singletonMapWithNotExistingValue = mapWriter2.done();

		// System.out.println(String.format("\n\ncachedNumbers = %s",
		// Arrays.toString(cachedNumbers)));
		// System.out.println(String.format("cachedNumbersNotContained = %s\n\n",
		// Arrays.toString(cachedNumbersNotContained)));
	
		// OverseerUtils.setup(JmhMapBenchmarks.class, this);
	}

	protected void setUpTestMapWithRandomContent(int size, int run) throws Exception {

		valueFactory = valueFactoryFactory.getInstance();

		IMapWriter writer1 = valueFactory.mapWriter();
		IMapWriter writer2 = valueFactory.mapWriter();

		int seedForThisTrial = BenchmarkUtils.seedFromSizeAndRun(size, run);
		Random rand = new Random(seedForThisTrial + 13);
		int existingValueIndex = rand.nextInt(size);

		int[] data = BenchmarkUtils.generateTestData(size, run);

		for (int i = size - 1; i >= 0; i--) {
//			final IValue current = producer.createFromInt(data[i]);

			writer1.put(producer.createFromInt(data[i]), producer.createFromInt(data[i]));
			writer2.put(producer.createFromInt(data[i]), producer.createFromInt(data[i]));

			if (i == existingValueIndex) {
				VALUE_EXISTING = producer.createFromInt(data[i]);
			}
		}

		testMap = writer1.done();
		testMapRealDuplicate = writer2.done();

		/*
		 * generate random values until a value not part of the data strucure is
		 * found
		 */
		while (VALUE_NOT_EXISTING == null) {
			final IValue candidate = producer.createFromInt(rand.nextInt());

			if (!testMap.containsKey(candidate)) {
				VALUE_NOT_EXISTING = candidate;
			}
		}

		testMapDeltaDuplicate = testMap.put(VALUE_EXISTING, VALUE_NOT_EXISTING).put(VALUE_EXISTING,
				VALUE_EXISTING);		
		
		testMapRealDuplicateSameSizeButDifferent = testMapRealDuplicate.removeKey(VALUE_EXISTING)
				.put(VALUE_NOT_EXISTING, VALUE_NOT_EXISTING);
	}

//	@TearDown(Level.Trial)
//	public void tearDown() {
//		OverseerUtils.tearDown();
//	}
//
//	// @Setup(Level.Iteration)
//	// public void setupIteration() {
//	// OverseerUtils.doRecord(true);
//	// }
//	//
//	// @TearDown(Level.Iteration)
//	// public void tearDownIteration() {
//	// OverseerUtils.doRecord(false);
//	// }
//
//	@Setup(Level.Invocation)
//	public void setupInvocation() {
//		OverseerUtils.setup(JmhMapBenchmarks.class, this);
//		OverseerUtils.doRecord(true);
//	}
//
//	@TearDown(Level.Invocation)
//	public void tearDownInvocation() {
//		OverseerUtils.doRecord(false);
//	}

//	@Benchmark
//	public void timeContainsKeySingle(Blackhole bh) {
//		bh.consume(testMap.containsKey(VALUE_EXISTING));
//	}

	@Benchmark
	@OperationsPerInvocation(CACHED_NUMBERS_SIZE)
	public void timeContainsKey(Blackhole bh) {
		for (int i = 0; i < CACHED_NUMBERS_SIZE; i++) {
			bh.consume(testMap.containsKey(cachedNumbers[i]));
		}
	}
	
	@Benchmark
	@OperationsPerInvocation(CACHED_NUMBERS_SIZE)
	public void timeContainsKeyNotContained(Blackhole bh) {
		for (int i = 0; i < CACHED_NUMBERS_SIZE; i++) {
			bh.consume(testMap.containsKey(cachedNumbersNotContained[i]));
		}
	}	

	@Benchmark
	public void timeIteration(Blackhole bh) {
		for (Iterator<IValue> iterator = testMap.iterator(); iterator.hasNext();) {
			bh.consume(iterator.next());
		}
	}

	@Benchmark
	public void timeEntryIteration(Blackhole bh) {
		for (Iterator<java.util.Map.Entry<IValue, IValue>> iterator = testMap.entryIterator(); iterator
				.hasNext();) {
			bh.consume(iterator.next());
		}
	}

//	@Benchmark
//	public void timeInsertSingle(Blackhole bh) {
//		bh.consume(testMap.put(VALUE_NOT_EXISTING, VALUE_NOT_EXISTING));
//	}	
	
	@Benchmark
	@OperationsPerInvocation(CACHED_NUMBERS_SIZE)
	public void timeInsert(Blackhole bh) {
		for (int i = 0; i < CACHED_NUMBERS_SIZE; i++) {
			bh.consume(testMap.put(cachedNumbersNotContained[i], VALUE_NOT_EXISTING));
		}
	}
	
	@Benchmark
	@OperationsPerInvocation(CACHED_NUMBERS_SIZE)
	public void timeInsertContained(Blackhole bh) {
		for (int i = 0; i < CACHED_NUMBERS_SIZE; i++) {
			bh.consume(testMap.put(cachedNumbers[i], cachedNumbers[i]));
		}
	}	

	@Benchmark
	@OperationsPerInvocation(CACHED_NUMBERS_SIZE)
	public void timeRemoveKeyNotContained(Blackhole bh) {
		for (int i = 0; i < CACHED_NUMBERS_SIZE; i++) {
			bh.consume(testMap.removeKey(cachedNumbersNotContained[i]));
		}
	}

	@Benchmark
	@OperationsPerInvocation(CACHED_NUMBERS_SIZE)
	public void timeRemoveKey(Blackhole bh) {
		for (int i = 0; i < CACHED_NUMBERS_SIZE; i++) {
			bh.consume(testMap.removeKey(cachedNumbers[i]));
		}
	}
	
	@Benchmark
	public void timeEqualsRealDuplicate(Blackhole bh) {
		bh.consume(testMap.equals(testMapRealDuplicate));
	}

	@Benchmark
	public void timeEqualsRealDuplicateModified(Blackhole bh) {
		bh.consume(testMap.equals(testMapRealDuplicateSameSizeButDifferent));
	}	 	

	@Benchmark
	public void timeEqualsDeltaDuplicate(Blackhole bh) {
		bh.consume(testMap.equals(testMapDeltaDuplicate));
	}

//	@Benchmark
//	@BenchmarkMode(Mode.SingleShotTime)
////	@Warmup(iterations = 0)
////	@Measurement(iterations = 1)
//	public void timeHashCode(Blackhole bh) {
//		bh.consume(testMap.hashCode());
//	}

//	@Benchmark
//	public void timeJoin(Blackhole bh) {
//		bh.consume(testMap.join(singletonMapWithNotExistingValue));
//	}

	public static void main(String[] args) throws RunnerException {
		System.out.println(JmhMapBenchmarks.class.getSimpleName());
		Options opt = new OptionsBuilder()
				.include(
						".*" + JmhMapBenchmarks.class.getSimpleName()
								+ ".(timeInsert)")
				.timeUnit(TimeUnit.NANOSECONDS)
				.forks(0)
				//.warmupMode(WarmupMode.INDI)
				.warmupIterations(0)
				.warmupTime(TimeValue.seconds(1))
				.mode(Mode.AverageTime)
				.measurementIterations(5)
				.param("dataType", "MAP")
				.param("run", "0")
//				.param("run", "1")
//				.param("run", "2")
//				.param("run", "3")
				//.addProfiler(CountingIntegerProfiler.class)
				//.param("producer", "SLEEPING_INTEGER")
				.param("producer", "COUNTING_INTEGER")
				.param("sampleDataSelection", "MATCH").param("size", "1024") // 1048576
				//.param("valueFactoryFactory", "VF_PDB_PERSISTENT_SPECIALIZED")
				.param("valueFactoryFactory", "VF_PDB_PERSISTENT_CURRENT")
				//.param("valueFactoryFactory", "VF_SCALA")
				//.param("valueFactoryFactory", "VF_CLOJURE")
				.resultFormat(ResultFormatType.CSV)
				.result("latest-results-main.csv")
				.build();

		new Runner(opt).run();
	}

}