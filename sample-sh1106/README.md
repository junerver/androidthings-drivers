## OLED Screen sample for Android Things

This sample demonstrates how to control the SH1106 OLED display using I2C with
Android Things.

## Pre-requisites

- Android Things compatible board
- Android Studio 2.2+
- 1 [SH1106 OLED screen](https://www.elecrow.com/download/SH1106%20datasheet.pdf)
- jumper wires
- 1 breadboard


# Build and install

On Android Studio, click on the "Run" button.

If you prefer to run on the command line, from this repository's root directory, type

```bash
./gradlew sample-sh1106:installDebug
adb shell am start com.leinardi.androidthings.sh1106.sample/.OledScreenActivity
```

If you have everything set up correctly, you will see a small bitmap slowly
moving in the screen. If you want to change the pattern displayed, change the
variable mMode in OledScreenActivity and deploy again. The sample has three
modes: DOTS, BITMAP (default) and CROSSHAIRS.


## License

Copyright 2017 Roberto Leinardi.

Licensed to the Apache Software Foundation (ASF) under one or more contributor
license agreements.  See the NOTICE file distributed with this work for
additional information regarding copyright ownership.  The ASF licenses this
file to you under the Apache License, Version 2.0 (the "License"); you may not
use this file except in compliance with the License.  You may obtain a copy of
the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
License for the specific language governing permissions and limitations under
the License.