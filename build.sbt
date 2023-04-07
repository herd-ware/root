/*
 * File: build.sbt
 * Created Date: 2023-02-25 01:11:34 pm                                        *
 * Author: Mathieu Escouteloup                                                 *
 * -----                                                                       *
 * Last Modified: 2023-04-05 08:51:49 am
 * Modified By: Mathieu Escouteloup
 * -----                                                                       *
 * License: See LICENSE.md                                                     *
 * Copyright (c) 2023 HerdWare                                                 *
 * -----                                                                       *
 * Description:                                                                *
 */


// ******************************
//           PARAMETERS
// ******************************
ThisBuild / scalaVersion     := "2.12.13"
ThisBuild / version          := "0.1.0"
ThisBuild / organization     := "herdware"

val libDep = Seq(
  "edu.berkeley.cs" %% "chisel3" % "3.4.3",
  "edu.berkeley.cs" %% "chiseltest" % "0.3.3" % "test",
  "edu.berkeley.cs" %% "chisel-iotesters" % "1.5.3"
//  "com.sifive" %% "chisel-circt" % "0.8.0"
)

val scalacOpt = Seq(
  "-Xsource:2.11",
  "-language:reflectiveCalls",
  "-deprecation",
  "-feature",
  "-Xcheckinit",
  // Enables autoclonetype2 in 3.4.x (on by default in 3.5)
  "-P:chiselplugin:useBundlePlugin"
)

// ******************************
//           PROJECTS
// ******************************
lazy val main = (project in file("."))
  .settings(
    name := "main",
    libraryDependencies ++= libDep,
    scalacOptions ++= scalacOpt,
    addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % "3.4.3" cross CrossVersion.full),
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
  )
  .dependsOn( common    % "test->test;compile->compile",
              draft     % "test->test;compile->compile",
              aubrac    % "test->test;compile->compile",
              abondance % "test->test;compile->compile",
              hay       % "test->test;compile->compile",
              io        % "test->test;compile->compile",
              cheese    % "test->test;compile->compile"
  )           
  .aggregate( common, draft, aubrac, abondance, hay, io, cheese)

lazy val common = (project in file("hw/common"))
  .settings(
    name := "common",
    libraryDependencies ++= libDep,
    scalacOptions ++= scalacOpt,
    addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % "3.4.3" cross CrossVersion.full),
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
  )

lazy val draft = (project in file("hw/draft"))
  .settings(
    name := "draft",
    libraryDependencies ++= libDep,
    scalacOptions ++= scalacOpt,
    addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % "3.4.3" cross CrossVersion.full),
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
  )
  .dependsOn( common  % "test->test;compile->compile",
              aubrac  % "test->test;compile->compile")           
  .aggregate( common, aubrac)

lazy val aubrac = (project in file("hw/core/aubrac"))
  .settings(
    name := "aubrac",
    libraryDependencies ++= libDep,
    scalacOptions ++= scalacOpt,
    addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % "3.4.3" cross CrossVersion.full),
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
  )
  .dependsOn( common  % "test->test;compile->compile",
              hay     % "test->test;compile->compile",
              io      % "test->test;compile->compile")           
  .aggregate( common, hay, io)

lazy val abondance = (project in file("hw/core/abondance"))
  .settings(
    name := "abondance",
    libraryDependencies ++= libDep,
    scalacOptions ++= scalacOpt,
    addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % "3.4.3" cross CrossVersion.full),
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
  )
  .dependsOn( common  % "test->test;compile->compile",
              aubrac  % "test->test;compile->compile",
              hay     % "test->test;compile->compile",
              io      % "test->test;compile->compile")           
  .aggregate( common, aubrac, hay, io) 

lazy val hay = (project in file("hw/mem/hay"))
  .settings(
    name := "hay",
    libraryDependencies ++= libDep,
    scalacOptions ++= scalacOpt,
    addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % "3.4.3" cross CrossVersion.full),
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
  )
  .dependsOn( common  % "test->test;compile->compile")        
  .aggregate( common)

lazy val io = (project in file("hw/io"))
  .settings(
    name := "io",
    libraryDependencies ++= libDep,
    scalacOptions ++= scalacOpt,
    addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % "3.4.3" cross CrossVersion.full),
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
  )
  .dependsOn( common  % "test->test;compile->compile")           
  .aggregate( common)

lazy val cheese = (project in file("hw/pltf/cheese"))
  .settings(
    name := "cheese",
    libraryDependencies ++= libDep,
    scalacOptions ++= scalacOpt,
    addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % "3.4.3" cross CrossVersion.full),
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
  )
  .dependsOn( common    % "test->test;compile->compile",
              draft     % "test->test;compile->compile",
              aubrac    % "test->test;compile->compile",
              abondance % "test->test;compile->compile",
              io        % "test->test;compile->compile")           
  .aggregate( common, draft, aubrac, abondance, io)